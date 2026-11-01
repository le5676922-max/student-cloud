# A 方升级交接草案（2026-11-01）

状态：**A 方建议，等待与 B 对齐；不是已上线 API 或已冻结合同。**

本轮已经实现 LOCAL 大文件传输增量与 A06 控制修复。以下类型尚未在产品实现中引入，不能据本文调用新版本、OCR、转换或 Agent 工具接口。

## 已有集成点

- 独立续传仍为 `/api/uploads/resumable`、`protocol=independent-v1`、4 MiB 分片、原有四种会话状态。
- LOCAL 文件上限动态返回，硬上限 1 GiB；目录配置 `metadata.maxDownloadFileSize` 提供经过授权的下载预算。远程和普通 multipart 保持 50 MiB，预览预算独立。
- 暂存预留是保守的四份发布峰值，**不是 B01 的用户容量账本**；仍依靠已有发布锁与实际用量检查。不生成虚构的 reservationId/operationId。
- 记忆 PUT 与单条 DELETE 现在必须携带读到的 expectedVersion；DELETE 使用查询参数。前后端需一起交付。

## A02 → B04/B05/B06/B07：FileVersionRef

建议外部对象含 `fileId,fileVersionId,storageKey,ownerScope,ownerId,aclRevision,sizeBytes,sha256,state`。

- fileId/fileVersionId 为不同 UUID；fileId 跨改名、移动保持稳定。
- ownerScope 建议 USER/COURSE，ownerId 对外使用十进制字符串；最终取值需双方确认。身份和范围由服务端解析。
- aclRevision 为正整数；sizeBytes 为非负整数字节；sha256 为64位小写十六进制。
- 只有 READY 且当前重新授权通过的版本才能供解析、索引与工具消费。目录快照 ID 不能替代 fileId。
- 物理对象键、宿主路径、云凭据不传给前端或智能服务。

## A02 → B04：ContentChangeEvent

建议含 `eventId,schemaVersion,fileId,fileVersionId,aclRevision,sequence,action,occurredAt,ownerScope,ownerId`。

- schemaVersion 首版为1；occurredAt 使用 UTC ISO8601。
- eventId 用于幂等消费，sequence 按单文件递增；同文件事实与 Outbox 必须在内容库同一事务提交。
- 建议动作覆盖创建、版本就绪、权限变化、回收、恢复、删除；动作枚举和删除/撤权先后规则由 A02/B04 共同确定。
- B04 按新旧版本和权限代次处理重复/乱序事件；智能服务不跨库读内容表。

## A03 → B04/B05：ExtractionResult

建议含 `fileId,fileVersionId,sourceSha256,parserVersion,state,totalPages,coveredPages,failedPages,truncated,segments[]`；段含 `segmentNo,page,position,text`。

- 首版遵守10 MiB、100页、10万字符预算；文本与 OCR 覆盖事实必须区分，部分失败标 PARTIAL。
- 处理输入必须是授权版本引用，不接受浏览器提供宿主路径或任意 URL。
- 通过受保护内容接口消费；源版本过期、删除、撤权或摘要不符的迟到结果不能发布到索引。
- Worker 需要实际隔离与 CPU/内存/磁盘/时间限制；本轮没有安装 OCR 引擎或开放处理中心。

## B01/B02 → A01/A02/A04/A05：等待提供

- `reserve/settle/releaseConfirmedFailure`：确认参数类型、幂等键绑定、实际峰值与永久容量的分账方式。
- `findOperation` 与 OperationReceipt：确认状态枚举、INDETERMINATE 核查方式、重试许可、结果版本列表及前端展示字段。
- 必须能区分“确定失败”“发布成功”“结果不确定”；不确定不能退还永久容量预留或再次发布。
- 现有续传 EXPIRED 包含取消，不应被误认为已存在 CANCELLED；会话状态与操作状态需明确映射。

## 继续开发的依赖

| A 任务 | 继续条件 |
|---|---|
| A01 完整阶段验收 | 接入 B01/B02；MySQL 8.4 并发/容量竞争与业务收据联调 |
| A02 版本与恢复 | 冻结版本/事件、预算/收据；明确旧资料迁移及路径占用策略 |
| A03 OCR | A02 版本引用、B04 消费协议及可验证的隔离 Worker 环境 |
| A04 图片转换 | Worker、B01预算、B02收据；同授权目录不覆盖发布 |
| A05 内容工具 | A02/A04 实现、B07经用户确认的计划与执行协议 |
| A06 剩余增量 | 随对应功能实际上线更新帮助；不提前描述未实现接口 |

上述共同边界确认后，用实际类型和合成样例替换本草案；不要分别实现两套同名合同。
