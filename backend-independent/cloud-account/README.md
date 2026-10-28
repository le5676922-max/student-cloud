# 独立账号模块（P1）

本模块只依赖独立 `cloud-common`、Spring JDBC/事务及 JDK 标准密码学，不导入原后端的实现、表或迁移。由入口模块显式创建 `AccountService(JdbcTemplate, TransactionTemplate, Clock, Duration)`。

## 已实现

- 基础身份表为 `sc_account`、`sc_session`、`sc_workspace`、`sc_platform_settings`；邮箱验证码、空间邀请/加入申请和注册资料分别由 `db/independent-email-schema.sql`、`db/independent-enrollment-schema.sql`、`db/independent-public-profile-schema.sql` 扩展。平台入口统一执行迁移及校验，现共5份迁移（含人工客服）。
- 数据库固定设置行的 `SELECT ... FOR UPDATE` 保护一次性初始化，拒绝覆盖已有账号；没有内置管理员或默认密码。
- 用户名、系统随机账号 ID、已配置邮箱登录；歧义标识拒绝登录。当前初始化只能创建一个全局管理员，不能由客户端指定角色。
- PBKDF2-HMAC-SHA256、随机盐、自描述密码格式、恒时摘要比较，以及输入和编码迭代边界。
- 高熵不透明会话 token；数据库仅保存 SHA-256 摘要。每次鉴权检查实时账号状态、角色、空间状态、过期时间及会话版本。
- 幂等登出；校验旧密码后变更密码，撤销该账号全部既有会话。每账号最多保留 32 个当前会话。
- 公开邮箱验证码注册、找回密码与空间入驻：普通管理员无审核创建空间；邀请码成员直接加入，空间名称成员须由该空间管理员审核。
- 可连续创建和撤销邀请码，允许有效期/人数为空表示长期有效/不限人数；验证码消费、邀请计数和注册状态变更随业务事务提交。
- H2 MySQL 模式仓储测试覆盖竞争初始化、失败不写入、登录、密码撤销、超时、禁用、标识歧义与秘密不落库等场景。

## API

| 方法 | 返回 |
| --- | --- |
| `install(siteName, username, password)` | 无凭据的 `AccountView` |
| `login(identifier, password)` | `LoginResult(account, token, expiresAt)` |
| `authenticate(token)` | 当前数据库解析的 `Actor` |
| `logout(token)` | 幂等，无返回 |
| `changePassword(actorId, oldPassword, newPassword)` | 无返回，撤销全部旧会话 |
| `installationStatus()` / `siteName()` | 安装状态 / 站点名称 |

`changePassword` 的账号 ID 必须来自服务端 `authenticate`，HTTP 接口不得直接信任客户端传入 ID。错误采用独立 `ApplicationFault`：`30000` 账号或密码错误、`42000` 需要登录、`41000` 输入或状态冲突，配合对应 HTTP 状态。

## P1 范围

### 登录后的账号和空间管理

`AccountAdministration(JdbcTemplate, PlatformTransactionManager, Clock)` 提供以下独立管理操作：

- `createWorkspaceAdministrator`：全局管理员事务创建空间及普通管理员；自动生成唯一 `SC` 账号 ID。
- `createMember` / `listWorkspaceMembers`：普通管理员只创建、查询本空间的普通成员；分页最多 100 条，支持账号 ID、用户名及邮箱的受限搜索。前端成员列表由 `WorkspaceMemberQuery` 返回 `{items,total,page}`，批量读取昵称与加入状态，避免 N+1 查询。
- `setMemberEnabled` / `resetMemberPassword`：仅处理本空间 `USER`；状态变化或密码重置递增会话版本并撤销旧会话，不返回密码编码。
- `listWorkspaceAdministrators`：全局管理员查看各空间普通管理员；全局管理员不能调用成员日常管理方法。

每次操作重新查询并锁定调用者数据库行，复查当前角色、账号和空间启用状态；不能仅信任旧的 `Actor.role`。管理入口中的 `Actor` 必须由可信认证服务提供。管理员手工创建成员可不填邮箱；填写时须规范化且唯一，不能因此标记为已验证。公开注册必须验证邮箱，不能由客户端指定全局管理员角色。用户名禁止冒用系统 `SC` 加十六进制的保留别名。

### 公开注册、验证码与找回

`EmailCodeService` 负责用途隔离、有效期、冷却/次数限制、摘要验证与一次性消费；`EmailCodeSender` 是发送端口，SMTP 实现在平台入口装配。`PublicRegistrationService` 协调邮箱证明、账号创建、找回与事务，`WorkspaceEnrollmentService` 管理空间创建、邀请码及加入审批。邮件发送不长期占用数据库事务。

名称加入的待审核成员不能用普通启用操作绕过审批。找回密码会撤销旧会话，但不改变停用或待审核状态；未注册邮箱的找回请求使用通用响应。注册、找回及 SMTP 默认关闭，配置见 [邮箱注册与 QQ SMTP 指南](../deploy/microservices/邮箱注册与QQ-SMTP配置.md)。

### 验证与剩余边界

本轮41项后端定向测试全部通过（AccountAdmin 5、Email 12、Public 13、Enrollment 7、MemberQuery 4），相关前端59项定向测试通过。临时真实 MySQL 与本地 SMTP 接收器经 Gateway 完成8组业务检查，覆盖管理员注册、成员入驻/审批、找回后会话撤销、成员分页和依赖失败等；详见 [邮箱注册与空间入驻实施记录](../../docs/邮箱注册与空间入驻实施记录-2026-10-26.md)。这不是完整测试套件或生产环境验收。

真实 QQ SMTP 尚未配置/验证，线上网站与旧数据库未改。多因素认证、登录图形验证码和邮箱修改仍未实现。禁止将旧账号密码编码直接复制入新 schema；后续需要显式、经过测试的数据迁移方案。当前不提供全局过期会话清理任务；登录会清理本账号过期会话，持续运行需由后续运维任务补充维护。
