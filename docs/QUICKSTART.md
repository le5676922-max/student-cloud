# 快速开始

本指南面向第一次从源码启动“学生云盘与智能资料管理平台”的开发者。默认方案是 **MySQL + Spring Boot 后端 + Vite 前端**；邮件、聊天模型、向量检索和 Redis 均按需配置。文中的密码、邮箱、域名只是占位符，不可直接用于正式环境。

## 1. 环境与目录

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| Java | **JDK 21** | `backend/pom.xml` 的编译目标为 21；IDE 项目 SDK、Maven 运行 JDK 也须一致 |
| Maven | 推荐 3.9.x | 仓库没有 Maven Wrapper，需自行安装 `mvn` |
| Node.js | **>= 22.13.0**，推荐 24 LTS | 由 `frontend/package.json` 声明 |
| pnpm | **11.19.0** | 与 `packageManager` 和锁文件一致 |
| MySQL | 推荐 8.0 | 本地与生产默认数据库；SQLite 仅为可选演示方案 |
| Docker | Engine + Compose v2 | 仅容器部署需要；只装 Docker CLI 不代表引擎已启动 |

在终端确认：

```text
java -version
mvn -v
node -v
pnpm -v
mysql --version
```

未安装 pnpm 时可执行 `npm install --global pnpm@11.19.0`。不要用 `npm install` 替代项目的锁定依赖安装。

克隆仓库后进入项目根目录，目录应包含：

```text
student-cloud/
├── backend/                 # Spring Boot 后端、数据库迁移脚本
├── frontend/                # Vue 3 前端
├── docker/                  # Compose 与生产环境配置模板
├── scripts/                 # 验证、演示启动、部署验收脚本
├── database/                # 手工重建脚本；不是正常安装/升级入口
└── docs/                    # 功能说明、测试记录与部署指南
```

以下 `cd backend`、`cd frontend` 等命令都以项目根目录为起点。请使用两个独立终端运行前后端。

## 2. 创建本地 MySQL 数据库

先启动你自己的 MySQL 服务，然后使用有建库权限的账户登录，`-p` 会交互式询问密码：

```bash
mysql -u root -p
```

在 **MySQL 提示符** 中执行下面的 SQL。密码请改为你自己的随机密码。这是给全新本地安装使用的示例，不是删除或重建已有数据的脚本。

```sql
-- 只创建项目专用数据库，不修改其它数据库。
CREATE DATABASE IF NOT EXISTS student_cloud
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建仅用于本机项目连接的账户；若同名账户已存在，先核查，不要删除旧账户。
CREATE USER 'student_cloud'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_RANDOM_PASSWORD';

-- Flyway 首次建表及后续迁移需要该项目库内的 DDL/DML 权限。
GRANT ALL PRIVILEGES ON student_cloud.* TO 'student_cloud'@'localhost';
EXIT;
```

不需要手工导入 `database/recreate_mysql.sql`。应用启动后由 Flyway 自动执行 `backend/src/main/resources/db/migration-mysql/` 中的迁移，并记录到 `flyway_schema_history`。**不要关闭 Flyway、修改已应用迁移的校验值或删除迁移历史来绕过报错**。已有同名数据库时先核查其用途及迁移状态，不能将重建脚本当成升级步骤。

## 3. 启动后端

后端默认配置文件为 `backend/src/main/resources/application.properties`，默认 profile 为 `prod`，默认 MySQL 地址为本机 `3306/student_cloud`，HTTP 端口为 `8080`。`prod` 在这里不表示已经完成生产部署，只是默认配置选择。

### Windows PowerShell

在第一个终端中执行：

```powershell
cd backend

# 仅对当前终端及其启动的后端生效，不把秘密写入源码。
$env:SPRING_DATASOURCE_USERNAME = 'student_cloud'
$env:SPRING_DATASOURCE_PASSWORD = 'REPLACE_WITH_YOUR_RANDOM_PASSWORD'

# 首次只验证基础云盘功能，可先关闭需要外部服务的功能。
$env:MAIL_ENABLED = 'false'
$env:TLGXY_CLOUD_AI_ENABLED = 'false'
$env:STUDENT_CLOUD_CACHE_TYPE = 'memory'

# 本地开发限制后端只监听环回地址；保持此终端运行。
mvn spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

### Linux / macOS shell

```bash
cd backend
export SPRING_DATASOURCE_USERNAME='student_cloud'
export SPRING_DATASOURCE_PASSWORD='REPLACE_WITH_YOUR_RANDOM_PASSWORD'
export MAIL_ENABLED=false
export TLGXY_CLOUD_AI_ENABLED=false
export STUDENT_CLOUD_CACHE_TYPE=memory
mvn spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

真实密码可通过终端安全输入、IDE 私有运行配置或服务器秘密管理注入；避免复制带真实值的命令到公开截图或共享终端历史。项目也支持忽略的 `backend/application-local-secrets.properties` 文件，模板是 `backend/application-local-secrets.example.properties`，但不是必需步骤。若已有该私密文件，`SPRING_DATASOURCE_PASSWORD` 可覆盖其密码；不要同时配置多份而忘记实际生效值。

数据库地址不同时，另设完整连接串：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/student_cloud?characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
```

这条连接串用于本机开发；远程数据库应另行配置 TLS、网络访问控制及相应 JDBC 参数，不要直接把数据库端口暴露公网。

等待出现应用启动成功日志，再访问 `http://127.0.0.1:8080/api/install/status`；应返回 JSON。仅看到 Maven 编译成功不代表应用已连接数据库。此时后端根页面可能没有静态前端，这是正常的，开发界面由下一步 Vite 提供。

## 4. 启动前端

在第二个终端，从项目根目录执行：

```bash
cd frontend

# 按 pnpm-lock.yaml 安装，避免安装时悄悄升级依赖。
pnpm install --frozen-lockfile

# 固定端口；端口被占用时明确失败，避免自动换端口后误访问旧页面。
pnpm dev --host 127.0.0.1 --port 5173 --strictPort
```

打开 `http://127.0.0.1:5173/login`。前端默认将 `/api`、`/user`、`/admin` 等后端请求代理到 `http://127.0.0.1:8080`，通常不必修改代码或前端 `baseUrl`。

如果后端改用其它端口，**先在启动 Vite 的终端中**设置 `CLOUD_API_TARGET`，再启动前端。例如 PowerShell 使用 `$env:CLOUD_API_TARGET = 'http://127.0.0.1:8081'`，shell 使用 `export CLOUD_API_TARGET='http://127.0.0.1:8081'`。修改 Vite 环境后需要重启开发服务。

为减少初次导航时的依赖重优化，Vite 会预扫描懒加载页面；首次依赖安装后的启动耗时可能较长。**Vite 开发服务器不用于生产发布**，正式部署使用构建产物和 Nginx。

## 5. 首次安装、登录及空间使用

1. 新数据库首次访问会引导至 `/install`。设置站点名称、全局管理员账号和密码；这是你自行创建的账号，**没有预设管理员密码**。
2. 初始化只允许执行一次。完成后前往 `/login`，使用刚创建的全局管理员账号登录。初次安装期间不要向不可信来源开放网站，以免被他人抢先初始化。
3. 全局管理员在控制台管理工作空间、空间人员、存储连接和站点设置。空间管理员仅管理本空间，普通成员按授权使用文件。
4. 邮箱服务未开启时仍可初始化和登录管理员，但公开邮箱注册、邮件验证码及邮件找回密码不能使用；不要用假验证码替代验证。
5. 开启邮箱后，注册空间管理员会创建新工作空间及本地“空间共享云盘”，无需审核；后端须具有私有持久化目录写权限。共享云盘按空间隔离，不是每人独立的私人目录。注册成员凭有效邀请码直接加入，凭空间名称则需空间管理员审核。全局管理员不能通过公开注册获得。
6. 新注册账号由系统生成唯一账号 ID，可使用邮箱或账号 ID 登录；已有用户名保持兼容。保存注册结果显示的账号 ID。
7. 若云盘没有存储源或没有权限，空页面不一定是服务异常。先检查该用户所属空间、存储连接状态及目录授权。不同存储协议提供的上传、移动、复制能力不完全相同。

业务页面默认要求登录，不提供匿名浏览云盘。登录、初始化、注册等必要公共接口不等于允许匿名访问文件。

## 6. 可选服务配置

下面均为**后端环境变量**，修改后需重启后端；Docker 中需重新创建对应容器使新环境生效。秘密不应写入前端的 `VITE_*` 配置，也不应提交 Git。

### QQ 邮箱 SMTP

| 变量 | 示例或要求 |
| --- | --- |
| `MAIL_ENABLED` | `true` |
| `MAIL_HOST` / `MAIL_PORT` | `smtp.qq.com` / `465` |
| `MAIL_USERNAME` | 你的发件邮箱 |
| `MAIL_FROM` | 通常与发件邮箱相同 |
| `MAIL_PASSWORD` | SMTP 专用授权码，**不是邮箱登录密码** |
| `MAIL_CODE_SECRET` | 自行生成、至少 32 字符的随机秘密；不要在重启时随机更换 |
| `MAIL_AUTH` / `MAIL_SSL` / `MAIL_STARTTLS` | `true` / `true` / `false` |

实际投递依赖邮箱服务、账号设置和服务器出站网络。上线后应自行测试注册验证码与找回密码邮件，不把测试夹具通过视为真实邮件投递成功。

### 小芽聊天模型与知识库

| 变量 | 用途 |
| --- | --- |
| `TLGXY_CLOUD_AI_ENABLED` | 设置 `true` 开启聊天模型接入 |
| `TLGXY_CLOUD_AI_API_KEY` | 供应商 API 密钥；遗留 `DEEPSEEK_API_KEY` 若同时存在会优先使用，应清理冲突配置 |
| `TLGXY_CLOUD_AI_BASE_URL` | 当前配置默认 `https://api.deepseek.com`；按实际供应商填写 |
| `TLGXY_CLOUD_AI_MODEL` | 填供应商实际支持的模型 ID；仓库默认值不保证任意账号均有权限 |

资料问答在用户授权范围内检索并展示来源。手动生成的 Agent 任务预览不依赖聊天模型；写操作仍受存储能力、权限及用户确认约束，不是无限制后台自动操作。

向量检索是**独立、默认关闭**的可选项，使用 `STUDENT_CLOUD_EMBEDDING_ENABLED`、`STUDENT_CLOUD_EMBEDDING_BASE_URL`、`STUDENT_CLOUD_EMBEDDING_API_KEY`、`STUDENT_CLOUD_EMBEDDING_MODEL` 和 `STUDENT_CLOUD_EMBEDDING_DIMENSIONS`。需要真实支持兼容 `/embeddings` 协议的服务，不能把聊天模型或聊天密钥直接当作嵌入模型配置。开启前应理解哪些文档内容会发送给供应商，并验证模型维度、费用及检索效果；已有资料不会因填入密钥自动批量上传。

### Redis 与 OCR

- 本地默认 `STUDENT_CLOUD_CACHE_TYPE=memory`，不必为了启动额外安装 Redis。生产 Compose 已配 Redis 元数据缓存和共享限流，变量见 `docker/.env.production.example`。
- Redis 缓存不代替实时权限校验，**也不代表 Sa-Token 会话、Agent 队列等已支持多实例**。当前生产方案保持一个后端实例。Redis 异常时限流和部分变更会按安全策略拒绝，不应擅自改为无限放行。
- 本地扫描 PDF 的可选 OCR 使用 Tesseract；Docker 后端镜像安装简体中文、英文语言包，默认 `STUDENT_CLOUD_OCR_ENABLED=false`。非容器环境须自行安装引擎和配置可执行路径；未验证的扫描样本不能承诺识别准确率。

## 7. 检查、测试与打包

以下命令从各自目录执行；测试通过不等于生产环境、真实云存储和外部模型均已验收。

```bash
# 在 backend/：单元/业务测试与 JAR 打包。
mvn -B verify

# 在 frontend/：类型、前端回归、代码规范与生产构建。
pnpm typecheck
pnpm test:unit
pnpm lint
pnpm build
```

后端输出 `backend/target/tongligong-student-cloud-4.6.0.jar`，前端输出 `frontend/dist/`。单独执行 `java -jar` 并不会自动替你发布前端。

PowerShell 用户还可在项目根目录执行 `./scripts/verify.ps1 -InstallDependencies`，该脚本依次执行后端测试打包、前端锁定依赖安装、类型检查、测试、构建、lint 和生产依赖审计；任何一步失败会停止。需先把 `java`、`mvn`、`node`、`pnpm` 加入 PATH。

可选 SQLite 演示：完成构建后，在项目根目录执行 `./scripts/start-local.ps1 -DisableAi`。此脚本明确启用 `local` profile，使用 `backend/.local-data/` 内的 SQLite，并从前端 `dist` 提供静态页面，默认访问 `http://127.0.0.1:8080`。**它不连接 MySQL，不是 MySQL 开发/生产的替代启动命令。**

## 8. Docker + Nginx 部署入口

正式部署请阅读 [Docker 生产部署与验收](Docker生产部署与验收-20261011.md)。仓库已包含单后端、MySQL、Redis、Nginx 四服务的源码构建配置，无需把本机 `target`、`dist` 或 `node_modules` 上传服务器。

在 Linux 服务器项目根目录，基础流程如下：

```bash
# 首次复制模板；若已有私密配置，不要覆盖它。
cp -n docker/.env.production.example docker/.env.production
chmod 600 docker/.env.production

# 编辑完整站点地址、数据库及 Redis 的三个不同随机密码。
# 不能保留 YOUR_SERVER_IP / REPLACE_... 等模板值。
nano docker/.env.production

# 仅验证语法，不打印展开后的密码；不代表镜像或容器已实测成功。
docker compose --env-file docker/.env.production -f docker/compose.production.yml config --quiet

# 从源码构建并启动；首次会拉镜像和下载依赖。
docker compose --env-file docker/.env.production -f docker/compose.production.yml up -d --build
docker compose --env-file docker/.env.production -f docker/compose.production.yml ps

# 只读查看最近后端日志，排查健康检查或数据库连接失败。
docker compose --env-file docker/.env.production -f docker/compose.production.yml logs --tail=100 backend
```

生产环境必须进一步按完整指南配置域名、证书及 `docker/compose.https.yml`，并完成只读部署验收；HTTP 仅用于受限的初始检查，真实账号密码应通过 HTTPS 传输。后端 `8080`、MySQL `3306`、Redis `6379` 不应发布到公网。服务器防火墙及云安全组应一起核查。

`mysql-data`、`business-data`、`upload-staging` 是持久卷。更新不需要删卷，**不要用 `down -v` 作为重启命令**。本地续传暂存目录必须与可浏览的云盘目录隔离，磁盘除暂存配额外还需留出最终文件空间。配置缓存/限流不代表已做真实服务器压测，默认资源限制不是并发承载承诺。

服务器有 Node 时可运行 `node scripts/deployment-smoke.mjs https://你的域名`，检查 SPA 刷新、公共启动接口、匿名访问拒绝及内部就绪地址隔离。脚本不创建账户、不上传删除文件，但它不是登录后完整业务或负载测试。

## 9. 常见故障

| 现象 | 检查方法 |
| --- | --- |
| MySQL `using password: NO` | 后端实际没有读到密码；检查启动该进程的终端/IDE 环境及私密配置覆盖，不要在截图或日志打印密码 |
| MySQL `Access denied` / `Unknown database` | 核查用户、host、密码、数据库名；账号不能只有查表权限而没有迁移权限 |
| `BeanCreationException`、`flywayInitializer` 失败 | 查找日志最底层 `Caused by`，通常应先处理数据库连接/迁移原因，不要因上层 `userService` 名称误判为登录代码错误 |
| `TypeTag :: UNKNOWN` 或编译器初始化失败 | 检查 `mvn -v` 与 IDE 使用的是否都是 JDK 21，避免高版本 JDK 与注解处理器不匹配 |
| 前端 500 / 代理 `ECONNREFUSED` | 先确认后端启动成功，再检查 `CLOUD_API_TARGET` 与实际端口；前端启动成功不能代替后端启动 |
| 5173 被占用 | 确认哪个项目在占用，不随意杀其它进程；选择新端口并同步后端允许来源及访问地址 |
| 页面空白或旧资源加载失败 | 检查控制台/网络错误、服务版本及浏览器缓存；不要用一直转圈掩盖实际接口失败 |
| 没有注册验证码 | 核查 SMTP 授权码、发件地址、SSL/端口、出站网络、垃圾箱与发送冷却；关闭邮件时注册不能完成 |
| 没有文件或无法上传 | 检查存储源、所属空间、目录权限、存储驱动能力和磁盘；云盘空状态不等于文件被删除 |
| Docker 后端 unhealthy / Nginx 502 | 用 `compose ps` 与后端日志查数据库、迁移、磁盘权限及资源；先解决后端就绪，不要直接关闭健康检查 |

若遇到 README 与较早的阶段性报告细节不一致，以当前源码、配置模板和最新验收记录为准。历史测试记录不等于当前服务器已验收；外部模型、邮件、对象存储、OCR、Redis 和真实并发均需按实际部署环境验证。
