# Student Cloud · Spring Cloud 独立后端

采用 **Spring Cloud Gateway + 账号与平台、文件与课程、智能助手三个独立服务**，开发版本 `0.1.0-SNAPSHOT`。新后端拥有独立构建、业务实现和数据库，不调用旧 `backend/` 的实现。

**微服务拆分已落地，整体产品仍在迁移，不能直接替换当前网站。** 前端保留现有主题和动画，只做必要接口适配；原线上环境不变更。

## 服务与数据

| 服务 | 默认端口 | 职责与独立数据库 |
| --- | --- | --- |
| `cloud-gateway` | 18093 | 显式路由、负载均衡、有限熔断、WebSocket代理，不连接业务数据库 |
| `cloud-platform-service` | 18101 | 初始化、账号/邮箱登录、邮箱注册与找回、空间加入与成员管理、人工客服；`student_cloud_platform` |
| `cloud-content-service` | 18102 | 本地文件、OSS/S3接入、目录索引、续传、本地回收与分享、授权容量、云盘审批、课程与资料；`student_cloud_content` |
| `cloud-intelligence-service` | 18103 | 模型问答、长期记忆、项目/审核知识、资料检索协调、反馈、确认式Agent与任务Outbox；`student_cloud_intelligence` |

每个服务一个独立 Java 进程。一个 MySQL 实例承载三个专属库和账号，服务不能跨库 JOIN 或读取其它服务表。内容和智能服务通过内部接口复核身份，智能服务通过内容接口确认课程资格。

## 技术与模块

Java 21、Spring Boot **4.1.2**、Spring Cloud **2025.1.4**；使用 Gateway、LoadBalancer、SimpleDiscoveryClient、CircuitBreaker/Resilience4j。版本依据 [Spring Cloud 官方兼容表](https://spring.io/projects/spring-cloud/) 选择。

两台阿里云 ECS 先使用固定内网地址发现服务，不额外运行 Nacos、Eureka、Seata 或消息队列。使用 ECS 不要求引入 Alibaba 全部组件。

16个 Maven 子模块包括：4个运行服务，以及 `cloud-common`、纯契约 `cloud-account-api`、身份实现 `cloud-account`、`cloud-drive`、`cloud-object-storage`、`cloud-drive-application`、`cloud-course`、`cloud-assistant`、`cloud-tasks`、`cloud-support`、`cloud-service-runtime` 和 `cloud-service-clients`。

开发期聚合 `cloud-server` 已移除。启动扫描限于各服务自己的包，Maven Enforcer 在构建时拒绝旧后端依赖和跨服务持久化实现；网关禁止嵌入业务或 JDBC。test scope 的身份夹具不进入生产 JAR。

## 配置和运行

- 默认绑定本机，各业务服务的 `local` profile 使用独立 H2 内存库，退出不保留数据。
- `mysql` profile 使用专属库，接受 `SC_DB_URL`、`SC_DB_USER`、`SC_DB_PASSWORD`，也支持服务前缀覆盖。
- 四个进程需相同随机 `SC_INTERNAL_KEY`，长度32–200字符；不读取旧项目秘密配置。
- `SC_PLATFORM_URI`、`SC_CONTENT_URI`、`SC_INTELLIGENCE_URI` 指定服务实例，网关另配置当前平台实例的 `SC_PLATFORM_WS_URI`。
- `SC_ALLOWED_ORIGINS` 是明确的浏览器来源；客服票据一次性、30秒有效，不在 URL 中传递用户登录 Token。
- 内容服务配置 `SC_MANAGED_STORAGE_ROOT`、`SC_COURSE_STORAGE_ROOT`；启用续传还需 `SC_UPLOAD_STAGING_ROOT` 与稳定的 `SC_CONTENT_NODE_ID`。三个已有绝对目录不得重叠；暂存目录不能被挂载为云盘。未配置独立暂存目录时，续传能力保持关闭。
- OSS/S3 连接由内容服务持有；凭据经 AES-GCM 加密，`SC_STORAGE_MASTER_KEY` 是独立的32字节Base64主密钥。自建服务需显式配置 `SC_STORAGE_ALLOWED_ENDPOINTS`；连接测试不修改Bucket CORS。配置、最小权限及操作边界见 [远程存储接入指南](../docs/OSS与S3远程存储接入指南.md)。
- `/healthz` 检查存活；业务 `/readyz` 检查本服务数据库，不表示全部远程依赖健康。

完整构建、本地 Docker 及两台2核4GB ECS 的内网布局见 [部署说明](deploy/microservices/README.md)。新配置独立于旧生产目录，不自动覆盖网站。

邮件投递默认关闭，邮箱注册和找回密码需要先为平台服务配置 SMTP。平台服务通过独立环境配置接入，不读取旧项目授权码。配置项、QQ SMTP 和注册/审批流程见 [邮箱注册与 QQ SMTP 配置](deploy/microservices/邮箱注册与QQ-SMTP配置.md)。独立后端尚未验证真实 QQ SMTP 投递。

## 当前已接通的业务

- 账号/邮箱登录、邮箱注册找回、空间邀请码/审批、成员目录和额度、全局与空间管理边界。
- LOCAL 文件上传下载、目录操作、元数据短期快照、SQL分页查询API、分块续传、7天回收站、必须登录的单文件分享。
- 私有文件预览：图片缩放旋转、PDF、Markdown、代码与文本、CSV/TSV、只读 DOCX/XLSX/PPTX、浏览器原生音视频，以及 OBJ/STL 几何模型。读取、格式检查、输出前重新授权及浏览器资源清理均纳入当前实现。
- OSS/S3/MinIO 协议连接的目录、读取、新建目录与50MiB内不覆盖上传；只读/写入探针分开，支持回读与精确探针清理、成员私有根及额度、持久写入状态和管理员主动核查。远程复制、移动、重命名、删除、回收和分享保持关闭。
- 个人课程TXT/MD/PDF/DOCX资料本地提取、源摘要与权限复核、相关片段检索、本轮主动同意私有上下文。
- 共享课程邀请、人数上限/到期/撤销、课程资料与创建者批准持续上传；普通管理员可加入其他人创建的课程。
- 小芽的模型问答、42条角色适用的项目说明、审核知识检索、明确保存的长期记忆，以及待确认预览、持久任务、结果核查和只生成预览的定时规则。
- “没有解决”反馈、管理员修订、AI润色草稿、人工审核发布、版本历史与撤回；私有来源禁止直接共享发布。

## 文件预览的范围

预览沿用当前界面主题和统一弹窗；文件通过当前账号的受保护接口读取，不需要第三方文档查看器，也不为预览开放匿名文件地址。

| 类型 | 当前实现与限制 |
| --- | --- |
| 图片 / PDF | 单文件最多20MiB；图片另限制2000万像素，PDF由浏览器本地查看器渲染 |
| 文本 / Markdown / CSV / TSV | 单文件最多1MiB、严格UTF-8；HTML和脚本仅展示源码，不执行 |
| DOCX / XLSX / PPTX | 单文件最多10MiB；本地提取文字、表格或幻灯片文字，只读，不还原完整排版、不执行宏或公式 |
| 音视频 | 单文件最多50MiB；完整文件读取后由浏览器解码，无自动播放，不保证所有编码可播放 |
| OBJ / STL | 单文件最多10MiB；受控几何预览、旋转缩放；不加载材质、贴图或外部资源 |

Office和模型解析使用有超时、可取消的Worker；关闭预览、切换目录或账号时回收请求、对象URL和GPU资源。具体格式、预算、操作方式及未覆盖项见 [私有文件预览功能与使用说明](../docs/私有文件预览功能与使用说明-2026-10-28.md)。

## 验证与记录

### 2026-10-28：私有预览升级

| 检查 | 已记录结果 | 覆盖边界 |
| --- | --- | --- |
| 内容服务及其Maven依赖测试 | 382项：378通过、0失败、4跳过 | `-pl cloud-content-service -am test`；不是其他业务服务的全量重测，4项跳过与当前Windows符号链接条件有关 |
| 前端完整Node测试集 | 984项通过，0失败、0跳过 | 包含预览、Worker、模型及生命周期回归 |
| Gateway + MySQL真实接口断言 | 78项通过 | 本地合成文件、格式和内容校验、匿名与跨空间拒绝；不是所有线上接口的验收 |
| 类型、构建与界面检查 | Vue类型检查、生产构建及本轮预览文件静态检查通过 | 浏览器实际验证文档、图片、PDF、WAV、模型、错误提示、键盘及390px窄屏 |

完整视频成功播放、真实OSS/S3凭据下的预览不在这轮验证覆盖内。详情及4个跳过用例名称见 [预览使用与测试记录](../docs/私有文件预览功能与使用说明-2026-10-28.md)。这些结果不表示全部业务完成验收或所有安全审计项已关闭。

### 2026-10-27：存储与业务联调（历史记录）

当日16模块完整Maven `package`及后续intelligence依赖构建通过；当时模块报告429项中425通过、4项因Windows符号链接条件跳过，前端Node 868项通过。这是当日记录，不能合并为2026-10-28的完整后端重测结果。SDK还使用真实HTTP客户端执行30项本机协议测试，包括零字节目录标记和512KiB文件流。详见 [存储接入与业务联调记录](../docs/OSS与S3接入及业务优化实施记录-2026-10-27.md)。

四个真实Java进程连接临时MySQL8.4.11、三个专属数据库账号，经Gateway完成14组远程及本地文件、课程、权限、Agent、反馈和知识库流程。远程服务和模型均使用本机协议夹具，未调用付费服务或发送真实用户文件；原生产环境没有改动。可重跑 `tools/verify_business_workflows.py`，脚本只创建和清理自己标记的测试资源。这里不把本机协议测试等同于真实OSS/S3账号验收。

[文件业务升级记录](../docs/文件业务升级实施记录-2026-10-26.md) · [知识库内容与维护手册](../docs/知识库内容与维护手册.md) · [知识库与Agent升级记录](../docs/知识库与Agent升级实施记录-2026-10-26.md) · [架构计划](../docs/Spring-Cloud微服务架构与实施计划.md)

## 尚未完成与能力边界

真实QQ SMTP/ECS外网投递、真实OSS/S3账号与多厂商兼容验收、WebDAV/FTP/SFTP驱动、向量嵌入服务、共享课程材料正文的独立检索索引、Redis/Caffeine业务缓存、完整压力测试与部署验收仍需完成。

预览已覆盖上表格式；尚未提供旧版DOC/XLS/PPT、完整Office排版与在线编辑、HLS/M3U8和FLV、Range分段媒体播放、FBX/GLTF等复杂模型及材质渲染。少数模型语法可能受前后端严格解析差异限制，具体以预览指南为准。

目录分页API已提供，原文件页尚未整体改为服务端分页。统计按已保存记录计数，不能当成全量操作审计或真实磁盘容量曲线。当前已知安全审计项仍应按专项记录继续整改，不能据本轮预览测试认定项目整体已完成安全加固。

Redis目前只有可选基础设施模板；知识检索是有界词法检索，未宣称已完成向量检索或模型训练。两台ECS的资源预算不是已测并发指标，未宣称性能优于其他项目。原前端及历史授权继续按许可证管理。
