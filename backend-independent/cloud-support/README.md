# 独立人工服务模块

新数据库上的 `sc_support_conversation` / `sc_support_message`，只依赖独立账号模型、公共失败类型和 Spring JDBC/事务。不访问旧后端、AI 历史、记忆或附件，不自动调用模型。此业务模块本身不开启网络或后台线程；WebSocket 传输和连接管理由 `cloud-platform-service` 中的独立适配器负责。

## 服务接口

`SupportService(JdbcTemplate, PlatformTransactionManager, Clock)`：

| 操作 | 范围 |
| --- | --- |
| `request(actor, subject, openingMessage)` | 成员或普通管理员明确创建本人的人工服务申请，连同用户选择发送的首条消息同事务保存 |
| `request(actor, subject, openingMessage, clientMessageId)` | 使用客户端消息标识幂等创建申请；HTTP 创建入口使用此形式 |
| `list(actor, offset, limit[, state])` / `count(actor[, state])` | 普通账号只看本人；全局管理员查看服务队列；可筛选状态 |
| `get(actor, id)` | 查看选定且获准访问的会话 |
| `messages(actor, id, offset, limit)` / `messageCount(actor, id)` | 按会话消息序号分页；每页最多 100 条 |
| `accept(globalAdmin, id)` | 接待 `WAITING` 会话进入 `ACTIVE`，由本次接待管理员回复 |
| `reply(actor, id, clientMessageId, body)` | 本人补充问题，或已接待该会话的全局管理员回复 |
| `close(globalAdmin, id)` | 关闭选定等待会话或自己已接待的会话，进入 `CLOSED` |
| `closeForRequester(actor, id)` | 申请者关闭自己的会话，不允许普通管理员关闭成员的会话 |

非本人不可查看或发送消息；同空间普通管理员也不能查看成员客服内容。全局管理员不能通过此服务读取成员其他聊天或文件。每个入口重新查询当前数据库账号、角色和空间状态；HTTP 层仍须先鉴权，不能从请求体直接构造可信 `Actor`。

消息正文最多 4000 个 Unicode 码点，保存纯文本。消息幂等键绑定会话、发送者和 `clientMessageId`，同键相同内容返回已有消息，同键异内容报 409。完整的已有消息重试在关闭后仍可返回原消息，但不能追加新内容。

服务申请的四参数入口同样支持幂等：以申请账号和 `clientMessageId` 记录创建结果及正文摘要，同键同内容返回原会话，不同内容返回 409。三参数便利方法每次生成新标识，不能拿它执行网络自动重试。每账号最多保留 10 个尚未关闭申请，申请创建在账号行锁下检查预算。

会话及消息写入使用数据库事务和会话行锁，消息序号稳定，不依赖同毫秒时间戳或内存计数。接待、关闭和发送时复查状态。关闭不删除历史；申请明确由用户提交，不自动复制 AI 历史或自动转人工。

## 当前边界

已在 `cloud-platform-service` 接入 `/api/support/conversations` 的列表、创建、详情、历史消息、回复、接待及关闭 HTTP 适配。消息响应以会话内单调序号作为浏览器游标，不能拿内部 UUID 按字符串比较推断顺序。现有前端的完整联调尚未完成。

## WebSocket 通知适配

`cloud-platform-service` 已实现 `SupportSocketHub` 和 `SupportSocketConfiguration`，适配现有前端的客服实时协议：

1. 登录后请求 `POST /api/support/socket-ticket`，取得路径 `/ws/support`、一次性票据及到期时间。签发和握手均检查明确配置的浏览器 Origin。
2. 通过 `/ws/support?ticket=...` 建立 WebSocket。随机票据仅有效 30 秒，以摘要作为服务端索引，兑换时原子移除；失败握手或断线重连均须重新申请。
3. 服务端将连接加入订阅集合后，首先发送经过鉴权的 `support.ready`。浏览器收到它后重新读取 HTTP 快照，以覆盖 HTTP 101 返回到订阅真正生效之间可能发生的变化；不能仅将浏览器 `onopen` 当作订阅已就绪。
4. 会话创建、回复、接待或关闭的业务事务成功返回后，HTTP 适配器发送 `support.changed` 信号，内容只有会话 ID，不广播正文、邮箱、账号资料或登录凭据。
5. 浏览器收到变更信号后，仍通过有鉴权的 HTTP 接口重新读取列表和消息。现有文本 `ping` / `pong` 用于保持连接；断线后按会话内消息序号补读。

连接绑定原始登录会话的不可自行构造 `SessionReference`；该引用内部只保存会话摘要，WebSocket 管理器不保留原始登录 Token，也不将 Token 放入 URL。每次消息变更通知发送前，重新检查会话有效期、密码版本、账号及空间状态，并再次确认会话访问权限。心跳回复前同样校验登录状态；空闲连接还会定期复核，以关闭已注销或撤销的会话。

默认资源上限：全实例最多 128 个连接、每账号 4 个；待兑换票据总数最多 1024 个、每账号最多 4 个，签发每账号每分钟最多 8 次；每连接最多积压 32 个不同会话的变更信号。通知采用异步发送并设置超时，积压或发送故障会关闭连接，由前端重新读取持久化数据。

**当前为单 API 实例的内存通知通道。** 票据、连接和通知不持久化，尚无 Redis 跨实例广播，不能据此直接宣称支持多后端实时消息同步。进程重启或通知丢失时，以数据库和鉴权 HTTP 查询为准；本轮经 Gateway 的基本握手、通知、票据重放与注销撤销检查已通过；完整浏览器重连仍需验收。"没有解决"反馈不自动创建客服申请，人工会话只由用户明确提交。

独立表结构资源 `db/independent-support-schema.sql` 包含会话、消息和申请幂等记录，由根工程追加到新的迁移流程，不能用于旧数据库。测试源码使用隔离 H2 MySQL 模式；本轮已通过隔离 MySQL 及 Gateway HTTP/WebSocket 基本检查，未执行完整测试套件或部署；详情见根工程 Spring Cloud 实施记录。
