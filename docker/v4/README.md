# V4 独立发布模板

本目录用于在 `/opt/student-cloud-v4` 准备第四版测试环境。Compose 项目为 `student-cloud-v4`，新数据库卷固定为 `student-cloud-v4-mysql`。它不读取第三版 `.env`，不导入第三版数据库，不操作第三版目录、容器或卷。

将本目录作为完整 bundle 放在服务器的独立暂存目录，然后由 root 执行 `bash prepare-v4.sh`。脚本只准备 V4 文件；发现 V4 卷存在时退出码为 `42`，发现已有 `.env` 或非空目标目录也拒绝覆盖。它不登录 ACR、不拉取镜像、不启动服务、不复制证书。脚本用 OpenSSL 新建两个数据库密码和一个邮件验证码 Secret，直接写入权限 `600` 的专用 `.env`，不输出其内容。不要用 `bash -v`、`tee`、`cat .env` 或 `docker compose config` 的非安静模式记录敏感配置。

准备前必须把当前 `frontend/nginx/routes.conf` 和 `frontend/nginx/proxy.conf` 原样复制到 bundle 的 `nginx/` 中；这两份配置保留分片上传限流、SSE、WebSocket 和代理头覆盖策略。HTTP 配置保留当前前端全部 http 层变量、日志格式、upstream 和限流 zone，另外通过 `acme.conf` 添加 ACME 路径。后续验证使用 `docker compose config -q`，避免展开 Secret 到终端。

## 针对当前版本的适配

- 当前镜像监听端口为 HTTP `8080`、HTTPS `8443`，测试映射为 `127.0.0.1:18081` 和 `127.0.0.1:18443`。默认配置只监听 HTTP，HTTPS 映射暂不提供 TLS。
- 后端保留镜像内置的 `/internal/ready` 就绪检查，MySQL 探针使用应用用户，密码只通过 `MYSQL_PWD` 进程环境传递。web 等待后端 healthy。
- 后端使用 UID/GID `10001`，`data/` 及其子目录权限为 `750`；独立的 `upload-staging/` 为 `700`，挂载到 `/app/upload-staging`，与用户存储隔离。
- 网络固定为 `172.21.0.0/24`，web 地址为 `172.21.0.10`，后端只信任该地址的 `/32`；`SERVER_FORWARD_HEADERS_STRATEGY=none` 保留真实 socket peer。此网段与检查时已有的本机网络、路由不冲突，执行前仍需确认没有新增冲突。
- AI 和邮件默认关闭，不需要 API Key 或 SMTP 授权码。配置包含环境变量占位符，不包含实际凭据。
- 新库完全由后端 Flyway 初始化，不导入 SQL。首次启动后应验证迁移成功、前端 SHA256 一致，并暂停等待用户通过 SSH 隧道手动完成 `/install`。

## 手动配置 QQ 邮件

完成 V4 `/install` 后，在本机项目根目录用 PowerShell 7 执行：

```powershell
pwsh -NoProfile -File .\scripts\configure-v4-mail.ps1 -SendTest
```

脚本先以已有 SSH 主机信任和指定私钥验证 `root@47.114.49.86`，再上传本目录的公开 Python helper。默认私钥路径是 `C:\Users\31717\.ssh\aliyun_student_cloud_20261025.pem`，可用 `-IdentityFile` 指定其他已有私钥。SSH 固定启用 `StrictHostKeyChecking=yes`、`BatchMode=yes`、`IdentitiesOnly=yes`；认证失败立即停止，不询问或猜测 SSH 密码，也不修改全局 SSH/PowerShell 配置。

按提示输入发件 QQ 邮箱，然后在隐藏提示中输入新获取的 **16 位英文字母 SMTP 授权码**。不要输入 QQ 登录密码，不要把授权码粘贴到聊天、命令参数、脚本或日志。授权码只通过 SSH 标准输入传递；本机不写凭据临时文件，退出时清除变量引用并释放、清零 BSTR。请直接在自己的终端运行，不开启调试跟踪或把完整会话录制到文件。

服务器 helper 只读取专用 `/opt/student-cloud-v4/.env`，保留其他配置和现有数据库 Secret 的字节内容。它先验证 `smtp.qq.com:465` 的 TLS 证书及 SMTP 认证，再以同目录 `0600` 临时文件原子替换 `MAIL_ENABLED`、`MAIL_USERNAME`、`MAIL_PASSWORD`、`MAIL_FROM` 四项；不生成凭据备份。此专用 `.env` 是用户允许的配置持久化位置，不要回显、提交或分享其内容。随后仅以 `--no-deps --wait --pull never` 重建 V4 backend，确认 healthy 后测试并 reload V4 web 的 Nginx，以刷新 upstream DNS；不重建 MySQL/web，不更改端口绑定。

`-SendTest` 通过 V4 loopback `POST /user/registration/email-code` 向发件 QQ 自己发送一封真实注册验证码；当前请求体只有 `email`，用途在服务端固定为 `REGISTER`。终端只显示安全状态和业务码，不显示邮箱、验证码、响应正文或原始异常。邮箱已占用、发送冷却或其他业务错误立即停止，不重试，也不注册、修改或删除账号。省略 `-SendTest` 仅配置服务。`ENV_OK` 之后失败表示邮件配置已保存，脚本不会自动回滚；应根据最后安全阶段检查后再决定是否重跑。

`TEST_OK business_code=0` 只表明应用接受并完成一次验证码发送；仍须在 QQ 邮箱确认收件，并由用户另行完成实际新账号注册、登录验收。脚本不执行 V3 操作，不读取 V3 配置或历史 QQ 授权码。

## 当前服务器资源预算

检查时服务器共有约 `3.5 GiB` 内存，可用约 `1.7 GiB`，无 Swap，磁盘可用约 `27 GB`。V3 加更早版本仍在运行。为保留它们，V4 使用以下限制，替代原计划的 1 GiB Java 堆：

| 服务 | 容器内存上限 | 主要约束 |
| --- | --- | --- |
| MySQL | 640 MiB | InnoDB buffer pool 128 MiB，最多 60 连接 |
| backend | 896 MiB | Java 堆 128–512 MiB，direct memory 96 MiB |
| web | 128 MiB | 分片缓冲使用现有有限 tmpfs 和连接限制 |

容器上限合计 `1664 MiB`，并非内存预留或整个工作集保证。初期工作集预计约 `800 MiB`，压力增长时仍需观察可用内存和 OOM；不要对并行环境执行无界压力测试。每个容器的 Docker JSON 日志限制为每文件 `10m`、最多 `3` 个文件。

本次 IBM Semeru / OpenJ9 运行时禁用默认及 OOM dump（`-Xdump:none`、`-XX:-HeapDumpOnOutOfMemoryError`），保留 OOM 退出，并将 core 大小限制设为 0。诊断文件仍不能视为已脱敏，禁止自动读取或转发其正文。该配置属于本次选定的 OpenJ9 镜像；以后改用其他 JVM 时须重新核对参数兼容性。

## HTTPS 与正式切换

`nginx/https.conf` 仅为未启用模板，证书路径对应宿主机 `/opt/student-cloud-v4/certbot/conf/live/jialechatweb.xin/`。准备脚本不复制证书、不更改正式 origin，也不启用该模板。用户完成初始化和功能测试后，另行复制既有证书、验证 TLS 并准备正式配置；必须再次取得用户对接管正式域名和 `80/443` 的明确确认，才能停止 V3 web 和重建 V4 web。第三版 MySQL、卷和目录始终保留，禁止 `down -v` 或清理卷。
