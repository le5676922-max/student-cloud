# 独立持久化任务模块

使用 `Spring JDBC + TransactionTemplate + Clock`，不引用旧后端，不启动后台线程、调度器或模型请求。表结构位于 `src/main/resources/db/independent-tasks-schema.sql`，由独立服务自己的迁移流程应用到新库。

## 已实现的状态接口

- `create(CreateTask)`：按可信服务端账号、空间和幂等键建任务；同键同内容返回同任务，同键不同内容返回 409。任务与 `TASK_CREATED` Outbox 事件同事务提交。
- `claim(workerId, leaseSeconds)`：有界查询与 CAS 领取；递增尝试次数和 fence。过期租约可重新领取，达到三次上限则结束失败。
- `heartbeat/complete/fail`：同时匹配任务、节点、fence、RUNNING 状态和未过期租约；过期或旧节点不能提交状态。
- `cancel(actorId, jobId)`：仅本人可取消；取消使旧 fence 立即失效。完成的任务不能取消。
- `get/list`：仅返回指定可信账号自己的任务，分页有界。
- `pendingOutbox/markOutboxPublished`：内部通知投递接口，至少一次语义；消费者必须按事件 ID 去重。此模块不自动发送通知。

状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED`；显式允许重试时重新排队，取消进入 `CANCELLED`。首次执行及租约过期重领均计入最多三次尝试。

`DOCUMENT_INDEX`、`FILE_ORGANIZE`、`MODEL_ANSWER` 目前仅为明确的任务分类，**尚未接入这些业务的实际执行器**。持久化 payload 上限 32 KiB，结果上限 16 KiB，不应包含密钥、原始文件或不必要的个人信息。失败原因只接受受控大写代码，不持久保存原始异常正文。

## 接入边界

HTTP 身份和空间必须来自认证与实时授权，不可直接信任浏览器的 actorId/workspaceId。`claim` 和 Outbox 方法只能用于受信任内部 Worker，不能公开成匿名接口。执行前重新检查账号、空间、文件权限与方案确认；目前创建记录不等于获得文件操作权限。

fence 保护数据库任务状态，不能凭自身阻止旧 Worker 写文件或再次调用模型。实际副作用必须额外使用操作幂等键、条件提交、文件发布或补偿核对；未接入该机制前不能自动重放不可幂等操作。取消不保证回滚已发生的副作用。

实例使用 UTC Clock，服务器需同步时间；租约没有部署成分布式时钟服务。数据库事务不能跨文件系统回滚。Outbox 只记录任务创建事件，本阶段不承诺每个状态变化均有通知事件。

本模块提供 H2 `MODE=MySQL` 状态测试源码，不使用用户数据库；根工程统一执行必要编译检查。本轮没有运行任务执行器、外部模型或性能测试。
