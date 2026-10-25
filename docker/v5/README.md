# V5 独立部署模板

实际上线状态、镜像 digest、检查结果与常用运维命令见[第五代部署执行记录](../../docs/V5部署执行记录-20261025.md)。正式服务器已经使用 HTTPS 和公开 80/443；下文的环回端口是初始验收配置。

本目录只为当前项目第五代 `v5.0.0` 提供无秘密部署配置。目标目录为 `/opt/student-cloud-v5`，Compose 项目为 `student-cloud-v5`，全新 MySQL 卷为 `student-cloud-v5-mysql`。不迁移 V3／V4 数据、不复用其卷、不删除旧目录。

## 准备与启动分开执行

将完整 bundle 上传到独立暂存目录，再由 root 运行 `bash prepare-v5.sh`。脚本只创建配置与目录，不拉镜像、不启停容器、不导入 SQL、不申请证书、不切换域名。发现既有 V5 卷时以 42 退出；发现非空目标、现有 `.env` 或符号链接也拒绝继续，不删除任何文件。

脚本在服务器生成新的数据库 root 密码、应用密码、Redis 密码和验证码 secret，仅保存到目标 `0600` 的 `.env`。禁止输出 `.env`、开启 shell xtrace、输出展开的 Compose 配置或完整容器环境。

正式启动前，由发布代理检查并将 `BACKEND_IMAGE`、`FRONTEND_IMAGE` 设置为已核验的 `仓库@sha256:...`；`MYSQL_IMAGE`、`REDIS_IMAGE` 也支持相同覆盖。默认标签不会覆盖已有发布记录，不能凭标签名称宣称镜像内容一致。

```bash
cd /opt/student-cloud-v5
# 只显示校验结果，避免将解析后的凭据输出。
docker compose --env-file .env -f compose.yaml config --quiet
# 确认所有镜像已经发布且其 digest 与本地清单一致后再拉取和启动。
docker compose --env-file .env -f compose.yaml pull
docker compose --env-file .env -f compose.yaml up -d --wait --wait-timeout 240
```

这些是方法说明，模板准备本身没有执行以上命令。部署状态以[执行记录](../../docs/V5部署执行记录-20261025.md)及实际服务器检查为准。

## 网络、反向代理和健康检查

- 仅 web 默认发布环回 `127.0.0.1:18082` 和预留 `127.0.0.1:18444`。默认 HTTP 模板未启用 TLS，不能把 18444 的映射当作 HTTPS 已验收。
- MySQL、Redis、后端不发布宿主机端口，处于 V5 独立网络。
- 网络 `student-cloud-v5-net` 使用 `172.22.0.0/24`，web 固定 `172.22.0.10`，后端仅信任 `172.22.0.10/32`。改变网段必须同步更新信任地址，并先检查 Docker 网络及 VPC 路由冲突。
- `server.forward-headers-strategy=none` 保留 socket peer；Nginx 覆盖转发头，不能信任浏览器提交的任意代理头。
- `routes.conf`、`proxy.conf` 为当前前端对应文件的快照，保留客服 `/ws/support`、AI SSE、续传分片限流及 `/internal` 隐藏规则。
- MySQL 使用应用账号运行 `SELECT 1`；Redis 以私有认证运行 PING；后端沿用镜像内 `/internal/ready` 检查真实数据库连接；web 沿用镜像 `/healthz`。
- 后端健康不代表邮件、外部模型、Redis 的业务路径和课程写入都已通过，需要分别验证。

主配置 `nginx/nginx.conf` 独立挂载到 `/etc/nginx/nginx.conf`：worker 数量自动选择，每 worker 2048 连接，进程文件描述符与 Compose nofile 上限均为 16384。启用 sendfile、TCP 合包、30 秒 keepalive、每连接最多 1000 请求、空闲读取／发送超时治理和适度静态描述符缓存，常见文本资源使用 gzip；SSE 仍在独立 location 禁止 gzip 和缓冲。

upstream 使用 `least_conn`、失败参数和最多 32 条空闲上游连接复用，但目前只有一个 `backend:8080`。这属于单实例反向代理与连接治理，**不是已经完成多实例负载均衡**。登录会话、Agent 队列、客服通知尚未完成分布式改造，不能仅往 upstream 增加副本。配置值不是并发承诺，特别是在约 3.5 GiB 内存服务器上，应根据真实资源观察再调整；不增加会误伤 SSE／上传的全站请求速率限制。

## 持久化与权限

| 服务器相对路径 | 容器路径 | 用途与权限 |
| --- | --- | --- |
| MySQL 命名卷 | `/var/lib/mysql` | V5 独立业务数据库，不能删除后重建来修复启动问题 |
| `data/` | `/data` | UID/GID `10001:10001`、目录 `750`，包含日志、运行主目录及业务文件 |
| `data/workspaces/` | `/data/workspaces` | 获批的受控工作空间本地存储 |
| `data/course-materials/` | `/data/course-materials` | 独立课程私有文件及课程上传暂存，与可浏览 LOCAL 根分离 |
| `upload-staging/` | `/app/upload-staging` | UID/GID `10001:10001`、`700`，持久保存续传分片和回执 |
| 宿主机 `/date/file/` | `/date/file` | 用户指定的本地云盘根，UID/GID `10001:10001`、`750`；独立绑定挂载，不放在容器临时层 |
| `config/` | `/app/config` | root 所有、组 10001，配置文件 `640`、只读挂载 |
| `certbot/conf/` | `/etc/letsencrypt` | 服务器证书目录，准备阶段 `700`，不下载私钥到开发机 |

后端根文件系统只读，不能遗漏 `courses.storage-root=/data/course-materials`，否则默认相对路径会进入只读 `/app`。不得把课程文件根或续传暂存根配置成任何 LOCAL 存储根及其别名。

本次服务器按用户要求额外使用 `/date/file`。复现该部署时，应先检查 `/date` 和目标路径不是符号链接、目标不存在或确实属于本项目，再创建目录并设置运行用户权限；不能依赖 Docker 自动创建 root 所有的目录。`prepare-v5.sh` 只准备项目内部目录，不会擅自接管这个绝对路径。页面填写的是容器路径，单独在宿主机建目录后仍需 Compose 挂载生效。

## 资源与容量预算

| 服务 | 内存上限 | 约束 |
| --- | --- | --- |
| MySQL | 640 MiB | buffer pool 128 MiB，最多 60 连接 |
| Redis | 160 MiB | maxmemory 96 MiB、`noeviction`、无持久化、无宿主机端口 |
| backend | 896 MiB | OpenJ9 堆 128–512 MiB，direct memory 96 MiB，禁用 dump/core |
| web | 128 MiB | 分片正文缓冲及临时目录有界 |

容器上限合计 1824 MiB，不是预留保证；需要先检查旧环境实际工作集及宿主机可用内存。模板不执行旧服务停止操作，释放资源与正式切换由主部署流程决定。

课程每创建者已用量默认上限设为 **2 GiB**，全站课程已用量为 **6 GiB**，通过外部配置映射到真实 `courses.max-owner-bytes`、`courses.max-total-bytes`。续传暂存全局设为 **4 GiB**，预留按声明文件大小两倍计费以容纳最终组装；并非每个人都可以使用 4 GiB。

当前 `ResumableUploadProperties` 没有“单用户暂存 1 GiB”属性，本模板不设置无效变量，也不宣称这个限制已实现。成员云盘容量是独立的账号／存储源授权，不等同于续传暂存预算。所有上限合计并非服务器磁盘预留，仍需观察实际剩余磁盘。

## Redis 与可选服务

Redis 用于展示元数据、短时总览缓存及共享限流，不作为权限来源、不替代 MySQL、不自动把登录会话和 Agent 队列改成多实例。namespace 固定为 `student-cloud-v5`，DB 1；与限流共用时使用 `noeviction`，避免淘汰计数导致绕过限额。断连时元数据读取回退数据库，写入及共享限流按已有 fail-closed 策略保护，不自动扩大权限。Redis 重建会丢弃缓存和短期限流计数，不丢失 MySQL 中的账号、课程或知识。

上线后的 V5 AI 流式子路径代理有一项部署适配：向后端发送 `Accept: */*`，让未登录等异常能正确返回 JSON，而成功控制器仍显式返回 SSE；关闭缓冲和 gzip 的规则继续生效。该处与前端通用路由快照不同，具体原因及验证见执行记录。

QQ 邮件和 DeepSeek 在新 `.env` 中均默认关闭。后续只能从已有授权私密配置或用户隐藏输入安全注入指定 `.env`，不能从聊天历史提取凭据、复制旧部署密码或输出内容。配置后分别执行真实收信和合成问题调用验收；禁止仅凭 `healthy` 宣称外部服务可用。embedding、OCR 未在此模板启用。

## HTTPS、初始化与回滚边界

首次空库由 Flyway 初始化，预期到 V80；检查 `/api/install/status` 后等待用户通过 SSH 隧道自行创建全局管理员，部署代理不选择或记录其密码。

`nginx/https.conf` 是休眠模板，准备阶段不复制证书、不替换 `default.conf`、不更改 `PUBLIC_ORIGIN` 和 80/443 映射。完成独立端口业务验收后，正式域名及 80/443 接管仍需用户明确确认。只停止占用正式端口的旧 web，保留旧后端、数据库、卷及文件，切换失败可恢复旧 web。不得 `down -v`、清理卷或无边界删除目录。
