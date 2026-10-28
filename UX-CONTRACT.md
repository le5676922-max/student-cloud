# UX Contract

## Product context

学生云盘服务中国大学课程资料和个人文件场景。产品语言 `zh-CN`，日期/时间 `Asia/Shanghai`；Element Plus 全局中文 locale 由 `frontend/src/App.vue` 统一提供。目标 WCAG 2.2 AA，不宣称仅凭静态检查已全部符合。

## Business-context sources

| Domain / scope | Authoritative source | Source type | Reviewed date |
| --- | --- | --- | --- |
| 存储权限、私有根、容量、探针 | `docs/OSS与S3远程存储接入指南.md`；独立内容服务 RemoteStorageAdministration / RemoteDriveService | 业务契约及服务端授权 | 2026-10-27 |
| 本地与远程删除/保留 | `docs/OSS与S3接入及业务优化实施记录-2026-10-27.md`；StorageAdministrationService | 生命周期契约 | 2026-10-27 |
| 全局统计的字段含义 | PlatformAdministrationService.overview；FileBusinessStatistics | 服务端查询契约 | 2026-10-27 |
| 会话与角色 | `frontend/src/modules/router.ts`；独立 AuthenticationFilter | 认证边界 | 2026-10-27 |
| 视觉和范围 | 用户明确选择的黑白灰几何角色主题；`DESIGN.md` | 当前任务决策 | 2026-10-27 |

本文件记录 UI 后果，业务权限和保留期仍由上述来源决定。无计费、支付或新增法律文案，不编造相关规则。

## Visual contract

`DESIGN.md` 解释意图，现有 `frontend/src/styles/main.css` 是 runtime token owner。继承 Element Plus、`admin.scss` 和现有导航 shell；本轮集中迁移存储管理与总览。历史页面暂未迁移的偏差须报告，不作为例外复制。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Select/Listbox | 连接编辑用 Element Plus ElSelect / ElOption；既有轻量本地筛选用原生 select | DESIGN.md 与本契约 | authored 连接实体选择 / native 无自定义弹层的轻量筛选 | 键盘、Escape、打开弹层、窄屏 |
| Date | 既有课程 datetime-local 输入 | 课程 API 时间语义与本契约 | native OS 弹层，保留手工输入 | 日期约束、时区及键盘 |
| Form | Element Plus ElForm / ElFormItem；storage-setup.mjs 与存储编辑页的既有 schema adapter | 本契约与后端字段 | create / edit | 关联错误、重复提交、保留输入 |
| Scrollbar | frontend/src/styles/main.css | DESIGN.md runtime mapping | 管理 shell 为既有滚动 owner | CSS token 测试、浏览器 computed style |
| Toast | Element Plus ElMessage；关键错误保留 inline alert | 本契约 | success / warning / info / error | 无密钥、状态可读、恢复动作 |
| CRUD | 既有 admin 路由、storage-list.js、storage-edit/index.vue | 后端生命周期与下方流程表 | 创建/编辑回列表；核查留页 | runtime tests + 浏览器走查 |
| 私有文件预览 | PrivateFilePreview.vue / usePrivatePreviewDialog.js | 文件列表的受保护预览元数据与 PreviewController | 图片、PDF、文本、Markdown、音视频、只读 Office、几何模型 | 真实鉴权接口、Worker、关闭清理、键盘及 390px 窄屏 |

本轮没有新增表格多选或日期选择器，不因此引入新控件。已有统计数字明细沿用原图表组件。原生轻量筛选接受操作系统拥有的弹层几何，不用 CSS 冒充自定义弹层；连接编辑的可搜索实体选择仍由 ElSelect 统一拥有。这是现有两类业务控件的明确归属，不将原生控件批量换成新控件。

## Flow ledger

| 操作 | Trigger / pending | 成功 / 反馈 | 失败 / 恢复 | Source |
| --- | --- | --- | --- | --- |
| 添加/编辑源 | 明确保存按钮；验证和保存期间禁止重复提交 | 服务端确认后返回原列表、保留已提交筛选 | 保留当前表单，inline 错误，不自动重放 | RemoteStorageAdministration.save |
| 测试连接 | 用户选择只读或读写；明确“测试”按钮 | 展示实际返回的验证范围与清理结果 | 不把测试当保存；未知结果提醒核查 | testConnection |
| 启用/停用 | 明确按钮/菜单；行级 pending | 刷新已确认状态 | 失败保持原状态，未知时重新读取 | storage-list.js / server enable |
| 删除本地配置 | 命名对象及后果的 ElMessageBox | 服务端确认后刷新 | 保留可恢复上下文；远程没有删除入口 | StorageAdministrationService.delete |
| 待核查写入 | 用户手动读取和检查，不自动轮询 | 肯定证据才显示完成；不上传 | PENDING 留存，不强行解冻 | RemoteDriveService.inspectPending |
| 全局统计 | 初始读取、用户刷新；内容区反馈 | 真实 available 数据与时间/范围 | 未提供与读取失败分别说明；不补 0 | overview / file-business API |
| 私有文件下载 | 原有下载操作及可选确认；支持时先选保存位置；并发最多两个 | 非阻塞百分比，完整写入并提交后提示“已保存”；不支持时沿用 Blob | 取消不发请求；中断/大小变化不提交部分文件、不自动再发 GET；原文件行保留供用户刷新重试 | useFileOperator.js / private-file-download.mjs |
| 私有文件预览 | 双击文件；仅接受与当前文件对应的同源预览路径；弹窗内显示读取状态 | 本地只读显示，格式工具有文字名称；Escape 关闭并恢复焦点 | 格式、预算、编码与权限错误在原弹窗内说明；关闭、切换账号/目录取消旧请求，终止 Worker 并回收 URL/GPU 资源；不自动改用第三方查看器 | PrivateFilePreview / PreviewContentPolicy / PreviewController |

## Navigation and responsive behavior

- 存储列表本身是后端最多200条的有界管理集合；本轮不伪装服务端分页。搜索及状态筛选在本地即时处理。
- 存储列表的已提交 query/filter 写入 URL，返回列表恢复；不把名称以外的凭据或连接字段放 URL。会话切换清空私有数据和异步结果。
- 中文输入组合期间 Enter 不触发保存、识别或跳转；显式提交不加入无意义延时。
- 可点击按钮必须有可读名称；状态 badge 是只读，不用点击文本偷偷改变启停。
- 现有 `.admin-main-content` 承担滚动；表单自然高度，窄屏保留字段及所有操作。无需修改整个应用的 height/overflow 来压缩卡片。
- 路由标题遵循 `页面名 | 学生云盘后台`；现有返回、侧栏和面包屑均可键盘使用。
- 跨云盘前进/后退保留目标目录；路由同步下拉框不额外跳根目录，用户主动下拉切源仍进入该源根目录。课程选择在 URL 边界兼容数字 ID 与查询字符串，直达/刷新保留对应课程。

## Async and recovery

默认等待服务端确认。重复提交受 pending gate 阻止；快照绑定当前账号、token、路由和目标。旧请求取消或失效，不能覆盖新页面。读取失败保留且标注旧数据；清空筛选即时生效并回到输入框。服务不可达、未登录、无权限、版本冲突和未知写入均不无限重试。

表单字段仅在内存保存；不把密钥写入 local/session storage。会话过期按既有登录流程处理，敏感数据不跨账号保留。离开未保存表单使用项目共享的应用内确认，真正页面卸载仅注册必要的 beforeunload；确认不重放后端请求。

## Validation and entry

表单 novalidate，保留输入语义及自动填充；以 ElForm 字段错误和服务端约束为准，不调用 reportValidity。首次提交错误焦点移到首个无效控件，字段有名称及关联帮助。Secret 默认掩码；显隐使用库可访问控件；文字域 resize none 并提供足够行数。

## Overlays and feedback

Element Plus ElMessageBox 负责确认与焦点恢复；不使用浏览器 alert/confirm/prompt。危险操作与保存分开，确认按钮写真实动作。错误不会自动消失；toast 不是唯一恢复入口。滚动/弹层沿用库层级，不自行给普通卡片设置巨大 z-index。

## Verification and migration

存储列表、编辑和总览迁移需覆盖 loading、empty、no-results、error、stale、权限、重复操作、IME、返回恢复、keyboard、窄屏与 reduced-motion。静态 premium audit、仓库 tests/typecheck/build、浏览器证据分别记录；任一未通过就明确报告，不据 audit 推断运行正确。

初始全应用扫描用于发现历史债务；只把本轮有修改的流程纳入逐项修复，避免对未授权的其他页面进行批量外观重写。
