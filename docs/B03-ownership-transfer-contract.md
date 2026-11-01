# B03 管理交接实现合同（2026-11-01）

## 关闭开关与范围

服务端 `student-cloud.ownership-transfer.enabled` 未配置即关闭；controller 与 service bean 不注册。前端组件 `enabled` 默认 false；宿主按 `VITE_OWNERSHIP_TRANSFER_ENABLED === 'true'` 显式启用。开关不绕过认证；生产启用前仍须完成 MySQL 8.4 与浏览器人工验收。

- platform：增量 V7 `db/independent-workspace-ownership-transfer-schema.sql`，只写 platform 账号、会话、空间和交接记录。
- content：增量 V16 `db/independent-course-ownership-transfer-schema.sql`，只写课程与课程交接。身份经现有 AccountDirectory 端口即时复核，不跨库 JOIN。
- `ownershipRevision` 是**管理所有权代次**，不是文件内容版本。源所有权代次不符返回 409；接受后 +1。
- 创建就是发起方的明确确认；只有接任者本人可 accept/reject，只有发起者本人可 cancel。接受在一笔本地事务内 PENDING→ACCEPTED→COMPLETED；中间态不对外承诺已提交。
- 到期 PENDING 由读取/动作惰性更新 EXPIRED；不会后台自动接受。七天内有效期。

## 同空间资格政策

空间：发起人必须是当前启用 TENANT_ADMIN，接任者必须是同一启用空间内已启用 USER。接任原子地提升接任者、将前任降为 USER、递增前任 session_version 并删除前任所有 session。一个事务内不存在空间无人管理的已提交状态。既有 platform 管理员启停接口也增加最后管理员保护：先锁 workspace 再锁 account，以锁定重读的当前启用管理员集合判断，最后一位不可停用，返回 409 `WORKSPACE_LAST_ADMIN`。已有全局禁用整个空间的生命周期不因此改为成员权限入口。全局 ADMIN 不能以全局身份交接或审批成员权限。

课程：接任者必须是同空间已启用 TENANT_ADMIN 且已有 ACTIVE 课程成员资格，不在交接中自动加入或调整账号角色。创建者当前可用；为避免空间交接后前任被降级而课程永久无从移交，**现有课程所有者即使已经是 USER，仍仅可交接其已经拥有的课程**，不能因此恢复更新、邀请、上传审批等管理接口。前任课程成员状态置 LEFT、上传资格清零，旧课程邀请全部撤销；既有管理接口每次按当前 owner_id 拒绝旧所有者。完成后前任不再自动拥有课程读取/上传资格。

接任前按现有 50 门课程及 2 GiB 课程总占用预算复核；与课程上传共用 storage-budget→course 行锁次序，再锁接任者 course-owner guard。非空课程不复制物理文件。

## 隐私不变量

不改个人文件归属、私有根、云盘用户 grant、问答或 Memory；不复制私有目录。仅转移公共课程内容的管理职责。前任 ADMIN/TENANT_ADMIN 身份都不代表接任者可读取其个人资料。接口不返回文件路径、内容、令牌、密码或云凭据；响应 no-store。接任者应自行登录读取收件箱，不能由发起者代确认。

## HTTP

两类基路径：

- `/api/workspace-admin/ownership-transfers`
- `/api/courses/{courseId}/ownership-transfers`

GET 基路径返回 `{actorId,resourceId,resourceName,ownershipRevision,canCreate,candidates,transfers}`；最多最近 50 条本人交接，候选最多 100 条。课程其他成员只能看到与自己有关的交接。空间收件箱允许 USER 当前身份读取，因此 Gateway 不应把该精确路径当作 TENANT_ADMIN 专用接口；真正资格仍由 platform 复核。

POST 基路径输入 `{toAccountId,expectedRevision,expiresAt,idempotencyKey}`。幂等键作用域为发起账号；同键不同参数 409，同资源只能存在一个 PENDING。POST `/{transferId}/accept`、`/reject`、`/cancel` 不接受客户端角色。相同已完成确认返回同一结果；EXPIRED 返回真实 EXPIRED 状态而非成功文案。

事务明确使用 READ_COMMITTED；资源行锁序列化创建/确认，确认取得锁后对交接记录使用锁定重读。H2 并发用例不冒充 MySQL 8.4 验收。

## 组件接入

```vue
<OwnershipTransfer resource-type="workspace" :enabled="ownershipTransferEnabled" />
<OwnershipTransfer resource-type="course" :resource-id="selectedCourse.id"
  :enabled="ownershipTransferEnabled" @completed="reloadCourses" />
```

`OwnershipTransfer.vue` 从服务端 snapshot 取 actorId 和资格，不接收客户端角色作为授权。沿用 DESIGN.md 灰白与语义 token，Element Plus 选择/确认/按钮；同一组件复用发起与接任收件箱。显式核对账号、影响范围和有效期；未知写入不自动重试，手动同参数重试复用同一幂等键及截止时间。账号/目标变化取消并丢弃旧结果，草稿仅在内存。

## 已验证与未验证

证据目录：`C:/Users/31717/Desktop/yun/.codex-tmp/developer-b-20261101/b03`。account/course 单元与 H2 并发、platform MVC、前端真实 Vue 组件运行时测试逐项记录；本目录外完整 reactor/frontend 的最终结果由主执行者汇总。

尚需真实 MySQL 8.4 并发/重启、端到端跨服务资格变更竞争、真实私有文件浏览器隔离，以及确认弹层键盘/390px/视觉走查。未运行真实生产或远程调用。候选 100 条和历史 50 条是当前明确上限，不声称完整无限目录；候选搜索/翻页尚未实现。

迁移不可反向删除审计记录来回滚所有权。关闭开关阻止新交接；已完成交接只有新发起双方确认才能再次移交，不能通过 git revert 恢复业务状态。
