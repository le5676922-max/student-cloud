# 文件与课程微服务

`cloud-content-service` 是独立运行的 Spring Boot / Spring Cloud 业务服务，监听本机 `18102`。它不包含账号持久化实现，也不连接平台或助手数据库。

## 数据所有权

- 独立数据库：`student_cloud_content`；本机默认使用同名 H2 内存库。
- 自有 V1–V4 迁移分别创建云盘与本地分配锁、课程与拥有者预算锁、课程资料、云盘申请审批。
- 对其它服务只保存逻辑 `accountId` / `workspaceId`，无跨库外键、跨库查询或账号表锁。
- 通过 `AccountDirectory` 的远程实现复核身份；身份服务不可用时拒绝受保护操作，不沿用过期授权。

云盘、成员授权、容量、课程邀请和上传审批仍在此服务内完成本地事务。课程和云盘放在同一服务是为了让资料存储、容量及成员资格校验保持一致，同时避免两台小规格 ECS 上增加无必要的文件跨服务传输。

## 配置

| 配置 | 用途 |
| --- | --- |
| `SC_CONTENT_PORT` | 默认 `18102` |
| `SC_BIND_ADDRESS` | 默认仅 `127.0.0.1`；容器中需显式配置 `0.0.0.0` |
| `SC_PLATFORM_URI` | 平台服务地址，默认 `http://127.0.0.1:18101` |
| `SC_INTERNAL_KEY` | 服务间认证密钥；正式环境应通过环境或挂载秘密配置提供 |
| `SC_MANAGED_STORAGE_ROOT` | 云盘受控目录；LOCAL 连接必须为该目录下互不包含的独立子目录 |
| `SC_COURSE_STORAGE_ROOT` | 私有课程资料目录，与可浏览云盘目录分开 |
| `SC_CONTENT_DB_URL` | MySQL profile 的独立数据库 URL，只允许 `student_cloud_content` 或其下划线后缀库 |
| `SC_CONTENT_DB_USER` / `SC_CONTENT_DB_PASSWORD` | 只具有本服务数据库权限的账号与密码 |

保留 `/api/drives`、`/api/courses`、云盘申请和本地盘审批等已实现 HTTP 契约。内部 `GET /internal/content/courses/{id}/read?accountId=...` 仅供持有服务凭据的后端检查课程资格，不返回课程资料或成员个人信息；网关不能向外开放此路径。

现有文件页面的列表、目录权限、新建、重命名、复制、移动、删除、上传和私有下载已通过独立核心适配。`/api/storage/list` 使用单独页面 DTO，原 `/api/drives` 保持不变；路径相对个人虚拟根，不返回物理路径。批量最多20项，先验证全部参数语法，再返回逐项操作结果。预览、分享和续传等能力如实保持关闭。

本地默认连接池 4、Tomcat 最大线程 32、POST/PUT共用两路上传、单文件 50 MiB。它们是资源预算，不是容量或性能验收数据。最终构建、10项文件HTTP/授权契约测试及经网关6组临时H2真实进程流程通过；旧网站不变更。详见 [文件管理实施记录](../../docs/独立微服务文件管理实施记录-2026-10-26.md)。
