# 学生云盘 Ubuntu 服务器部署指南

> 适用项目：当前工作区的学生云盘，后端版本 `4.6.0`，Java 包名 `im.tlgxy.cloud`。
> 编写日期：2026-09-20。本文以 **Ubuntu Server 24.04 LTS、单服务器、独立域名、MySQL 8.0** 为基准。  
> 本文已按当前项目代码核对配置和接口，但没有在你的 Ubuntu 服务器上实际执行；上线时仍需完成文末验收。

## 快速导航

- [准备事项](#2-准备事项) → [初始化服务器](#3-初始化-ubuntu-环境) → [本地构建与上传](#4-本地构建前后端)
- [创建数据库](#5-创建生产-mysql-数据库) → [新装或迁移数据](#6-选择数据初始化方式)
- [后端配置](#7-配置生产后端) → [systemd 服务](#8-使用-systemd-管理后端) → [Nginx 与 HTTPS](#9-配置-nginx-和-https)
- [首次安装与角色](#10-完成首次安装和三类角色配置) → [存储与域名](#11-配置本地存储站点域名和外部服务) → [AI Agent](#12-配置-ai-agent-与知识库)
- [上线验收](#13-上线验收清单) → [备份恢复](#14-备份与恢复) → [更新回滚](#15-更新版本与回滚)
- [运维命令](#16-日常运维命令) · [故障排查](#17-常见故障排查) · [最终核对](#18-上线前最后核对)

## 1. 部署方案与阅读顺序

生产环境不需要一直运行 `pnpm dev`，也不需要开放 `5173`。前端打包后交给 Nginx，后端以 systemd 服务运行。

```text
浏览器
  └─ HTTPS :443 → Nginx
                   ├─ 页面、JS、CSS → 前端 dist
                   └─ 接口、下载、回调 → Java 后端 127.0.0.1:8080
                                          ├─ MySQL 127.0.0.1:3306
                                          ├─ /var/lib/student-cloud/storage
                                          └─ 可选：模型 API / 第三方存储
```

建议从第 2 节顺序执行。只有迁移旧数据时才执行第 6 节的迁移分支；日后更新使用第 15 节。

| 情况 | 应选择的方式 |
| --- | --- |
| 服务器第一次部署，不保留本机数据 | 新建空数据库，由 Flyway 自动建表，再访问安装页 |
| 要保留当前电脑上的用户、配置、文件 | 先导出 MySQL 和真实文件，导入服务器，再启动后端 |
| 已部署过，只更新代码 | 备份 → 新建版本目录 → 切换版本 → 验收，不能重建业务数据库 |
| 仅在 Windows 本机开发 | 阅读同目录《前后端启动说明.md》，不要混用本文 Linux 命令 |

**重要：** `database/recreate_mysql.sql` 和 `重建MySQL数据库.sql` 是重建脚本，包含破坏性操作，不用于正常部署、迁移或升级。已有数据库绝不能因为启动报错就直接重建。

## 2. 准备事项

### 2.1 环境建议

以下资源是小规模使用的起步建议，不是容量承诺；大量并发上传、预览和模型调用应另行压测。

| 项目 | 本文采用的配置 |
| --- | --- |
| 系统 | Ubuntu Server 24.04 LTS |
| 计算资源 | 建议至少 2 核、4 GB 内存；文件较多时使用独立数据盘 |
| Java | Java 21；服务器只运行 JAR 时安装 JRE 即可 |
| 数据库 | MySQL 8.0，同机部署 |
| Web 服务 | Nginx，提供 HTTPS、静态文件和反向代理 |
| 构建环境 | 本地 JDK 21、Maven、Node.js、pnpm |
| 项目前端约束 | `package.json` 声明 Node.js `>=20.19.0`、pnpm `11.19.0` |
| 已使用的本地构建环境 | Node.js `24.16.0`、pnpm `11.19.0`、Maven `3.9.12` |
| Redis | 本文单实例方案不需要，项目未配置 Redis 时使用内存缓存 |

运行环境与构建环境分开，因此服务器不用安装 Node.js、pnpm、Maven，也不要上传 Windows 的 `node_modules`。Java 21 软件包可由 Ubuntu 24.04 软件源提供。[Ubuntu Java 21 软件包说明](https://packages.ubuntu.com/noble/openjdk-21-jre-headless)

### 2.2 先替换本文中的示例值

| 示例值 | 替换为 |
| --- | --- |
| `cloud.example.com` | 你的真实云盘域名 |
| `SERVER_IP` | 服务器公网 IP |
| `deploy` | 服务器上有 sudo 权限的 SSH 用户；若实际是 `ubuntu`，就用 `ubuntu` |
| `203.0.113.10` | 你当前电脑出口的公网 IPv4，用于首次安装白名单 |
| `admin@example.com` | 接收证书通知的真实邮箱 |
| `REPLACE_WITH_RANDOM_DATABASE_PASSWORD` | 新生成的数据库强密码 |
| `20260920-001` | 本次发布编号；后续发布必须使用新编号 |

本文的 `bash` 命令在 Ubuntu SSH 终端执行，`powershell` 命令在本机 PowerShell 执行。配置代码块是粘贴到指定文件中的内容，不能作为终端命令逐行执行。

### 2.3 域名和端口

将域名 A 记录指向服务器公网 IPv4。只有服务器 IPv6、Nginx 和防火墙都配置好时才添加 AAAA 记录，错误的 AAAA 会影响访问和证书签发。

本方案直接部署在域名根路径，不放在 `/cloud/` 等子目录；子目录部署还需要另行调整前端路由和所有 API、分享及回调地址。

| 端口 | 公网是否开放 | 用途 |
| --- | --- | --- |
| 实际 SSH 端口，常见为 22 | 建议只允许管理员 IP | 服务器运维 |
| 80/TCP | 是 | 证书验证和 HTTPS 跳转 |
| 443/TCP | 是 | 网站入口 |
| 8080/TCP | 否，只绑定本机 | Java 后端 |
| 3306/TCP | 否，只绑定本机 | MySQL |
| 5173/TCP、6379/TCP | 否 | 本方案不使用公网开发服务或 Redis |

如果已有其他网站、数据库或防火墙规则，不要直接覆盖它们；先确认域名、端口和目录没有冲突。

## 3. 初始化 Ubuntu 环境

### 3.1 安装运行组件

先通过 SSH 登录服务器：

```powershell
ssh deploy@SERVER_IP
```

在 Ubuntu 中执行：

```bash
lsb_release -a
sudo apt update
sudo apt install -y openjdk-21-jre-headless mysql-server nginx certbot \
  curl jq rsync unzip openssl nano dnsutils fonts-dejavu-core
java -version
mysql --version
nginx -v
certbot --version
```

确认 Java 主版本为 `21`。如果系统存在多个版本，可用 `sudo update-alternatives --config java` 选择 Java 21，再执行 `java -version`。

软件包安全更新应纳入日常维护；已有服务器的系统升级、重启应安排维护窗口，不建议在未知业务状态下直接执行整机升级。

### 3.2 防火墙和云服务器安全组

先检查实际 SSH 端口，确认另一条 SSH 连接可以登录，再启用防火墙：

```bash
sudo sshd -T | grep '^port '
sudo ufw status numbered

# 若 SSH 不是 22，请替换为实际端口。
# 若出口 IP 不固定，先按实际运维条件调整规则，避免锁死 SSH。
sudo ufw allow from 203.0.113.10 to any port 22 proto tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

同时在云厂商控制台检查安全组。不要开放 `8080`、`3306`、`5173`，不要执行 `ufw reset` 清空现有业务规则。已有宽泛放行规则时，要审查并收紧，而不是认为新增规则会自动覆盖旧规则。[Ubuntu 防火墙说明](https://documentation.ubuntu.com/server/how-to/security/firewalls/index.html)

### 3.3 创建运行账户和目录

`studentcloud` 只运行后端，不允许 SSH 登录：

```bash
sudo useradd --system --user-group --home-dir /var/lib/student-cloud \
  --shell /usr/sbin/nologin studentcloud

sudo install -d -o root -g root -m 0755 /opt/student-cloud/releases
sudo install -d -o root -g studentcloud -m 0750 /etc/student-cloud
sudo install -d -o studentcloud -g studentcloud -m 0750 \
  /var/lib/student-cloud \
  /var/lib/student-cloud/storage \
  /var/lib/student-cloud/tmp \
  /var/log/student-cloud
sudo install -d -o root -g root -m 0700 /var/backups/student-cloud
```

若账户已存在，先用 `id studentcloud` 和 `getent passwd studentcloud` 核实，不要删除重建。

目录约定：

```text
/opt/student-cloud/
  releases/20260920-001/app.jar        后端构建产物
  releases/20260920-001/frontend/      前端 dist 的内容
  current -> releases/20260920-001     当前运行版本
/etc/student-cloud/
  application.properties             外部生产配置
  secrets.env                        数据库密码、模型 API Key
/var/lib/student-cloud/
  storage/                           真实用户文件
  tmp/                               Java 临时文件
/var/log/student-cloud/               应用日志
/var/backups/student-cloud/           本机备份，另需复制到异机
```

程序版本和真实文件必须分离。升级只能切换 `/opt/student-cloud/current`，不能覆盖或删除 `storage`。

## 4. 本地构建前后端

### 4.1 检查并打包

在当前 Windows 项目根目录执行。推荐使用项目现有验证脚本，它会依次运行后端测试、后端打包、前端类型检查、单元测试和生产构建：

```powershell
Set-Location 'C:\Users\31717\Desktop\yun\student-cloud-github'

$env:JAVA_HOME = 'D:\develop\Java\jdk-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

java -version
mvn -version
node --version
pnpm --version

powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1 -InstallDependencies
if ($LASTEXITCODE -ne 0) { throw '验证或构建失败，停止发布。' }
```

上述 JDK 路径是当前电脑路径，换电脑后需修改。`-ExecutionPolicy Bypass` 只作用于这次子进程，不要求永久修改系统执行策略。

也可分别在后端目录运行 `mvn -B package -DskipTests=false`，在前端目录运行 `pnpm install --frozen-lockfile`、`pnpm run typecheck`、`pnpm run test:unit`、`pnpm run build`；每一步成功后才继续。

不要为了打包跳过失败测试。项目现有 ESLint 仍有历史问题，默认验证脚本不包含全量 lint；详见《项目测试报告.md》，不要把构建通过等同于安全审计通过。

本文记录的是当前项目可对应的部署方式，不代表现有依赖版本已经完成生产安全审计。正式公网开放前，应另做依赖漏洞检查，评估框架和组件的安全更新，并在隔离环境回归后发布。

当前产物应为：

```text
backend/target/tongligong-student-cloud-4.6.0.jar
frontend/dist/index.html
frontend/dist/assets/...
frontend/dist/student-cloud.config.json
```

前端 `public/student-cloud.config.json` 中保留 `"baseUrl": ""`、`"router": { "mode": "history" }`，并保留其他原有字段。同域部署不应写入 `http://127.0.0.1:8080`，否则用户浏览器会请求用户自己的电脑。

`CLOUD_API_TARGET` 是 Vite 开发代理使用的变量，不会代替生产 Nginx 配置。

### 4.2 上传产物

在 Windows PowerShell 项目根目录打包前端：

```powershell
tar -czf .\student-cloud-frontend-20260920-001.tar.gz -C .\frontend\dist .
if ($LASTEXITCODE -ne 0) { throw '前端归档失败。' }

Get-FileHash .\backend\target\tongligong-student-cloud-4.6.0.jar -Algorithm SHA256
Get-FileHash .\student-cloud-frontend-20260920-001.tar.gz -Algorithm SHA256

ssh deploy@SERVER_IP 'mkdir -p ~/student-cloud-upload/20260920-001'
scp .\backend\target\tongligong-student-cloud-4.6.0.jar deploy@SERVER_IP:~/student-cloud-upload/20260920-001/
scp .\student-cloud-frontend-20260920-001.tar.gz deploy@SERVER_IP:~/student-cloud-upload/20260920-001/
```

检查各命令是否成功。使用非默认 SSH 端口时，`ssh` 使用 `-p`，`scp` 使用大写 `-P`。

不要上传整个工作区、数据库备份、`.codex-tmp`、日志、私钥或 `node_modules` 到 Nginx 网页目录。当前仓库的数据库密码和第三方 OAuth 凭据已改为外部配置；发布前仍应排查真实密钥，对曾泄露或共用的凭证进行轮换。外部配置不会从 JAR 中擦除已经打包的原始配置，所以 JAR 也不能放进公开下载目录。

### 4.3 安装到版本目录

以下操作在 Ubuntu 执行。本节命令块使用 Bash 的 `set -e`，失败后不要继续后面的发布步骤：

```bash
set -e
cd ~/student-cloud-upload/20260920-001
sha256sum tongligong-student-cloud-4.6.0.jar student-cloud-frontend-20260920-001.tar.gz
# 先人工对比上一步 Windows 输出的 SHA256，再继续。

# 要使用全新的目录；若已存在，换发布编号，不要覆盖旧版本。
test ! -e /opt/student-cloud/releases/20260920-001
mkdir frontend-unpack
tar -xzf student-cloud-frontend-20260920-001.tar.gz -C frontend-unpack
test -f frontend-unpack/index.html
test -f frontend-unpack/student-cloud.config.json

sudo install -d -o root -g root -m 0755 \
  /opt/student-cloud/releases/20260920-001/frontend
sudo install -o root -g studentcloud -m 0640 \
  tongligong-student-cloud-4.6.0.jar \
  /opt/student-cloud/releases/20260920-001/app.jar
sudo rsync -a --chown=root:root --chmod=D755,F644 \
  frontend-unpack/ /opt/student-cloud/releases/20260920-001/frontend/

# 仅首次部署使用。已有 current 时不要覆盖，改用第 15 节升级流程。
sudo ln -s /opt/student-cloud/releases/20260920-001 /opt/student-cloud/current
readlink -f /opt/student-cloud/current
```

Nginx 只读取 `frontend`，后端账户只读取 JAR，不赋予程序修改发布产物的权限。

## 5. 创建生产 MySQL 数据库

### 5.1 启动数据库并检查监听地址

```bash
sudo systemctl enable --now mysql
sudo systemctl status mysql --no-pager
sudo ss -ltnp | grep -E ':(3306|33060)\b'
```

数据库应该仅监听回环地址。若监听 `0.0.0.0` 或 `[::]`，先检查 `/etc/mysql/mysql.conf.d/mysqld.cnf`，确认是否有其他业务依赖，再将本机部署的监听地址配置为：

```ini
[mysqld]
bind-address = 127.0.0.1
mysqlx-bind-address = 127.0.0.1
```

修改后在可接受的维护窗口重启 MySQL，并再次检查监听地址；不要直接覆盖整个现有配置文件。[Ubuntu MySQL 安装说明](https://ubuntu.com/server/docs/install-and-configure-a-mysql-server)

### 5.2 创建空库和专用账户

先生成一段随机数据库密码，并存入密码管理器，不要发到聊天记录或提交到代码仓库：

```bash
openssl rand -hex 32
sudo env MYSQL_HISTFILE=/dev/null mysql
```

在 MySQL 提示符中执行，必须把密码占位符替换成刚生成的值：

```sql
CREATE DATABASE student_cloud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'student_cloud_app'@'127.0.0.1'
  IDENTIFIED BY 'REPLACE_WITH_RANDOM_DATABASE_PASSWORD';
GRANT ALL PRIVILEGES ON student_cloud.* TO 'student_cloud_app'@'127.0.0.1';
SHOW GRANTS FOR 'student_cloud_app'@'127.0.0.1';
EXIT;
```

专用账户的权限限定在 `student_cloud` 库，不授予全局权限或 `GRANT OPTION`。Flyway 建表、升级需要 DDL 权限，因此这里授予该库范围内的权限。

库或账户已存在时，应先核对用途和权限，不要删除后重建，也不要通过忽略报错假定密码已生效。

测试应用账户的 TCP 连接：

```bash
mysql -h 127.0.0.1 -P 3306 -u student_cloud_app -p student_cloud \
  -e 'SELECT DATABASE(), CURRENT_USER();'
```

密码在提示符中输入，不要使用 `-p明文密码`。

## 6. 选择数据初始化方式

### 6.1 全新安装

保持数据库为空，直接继续第 7 节。后端启动时会选择 MySQL 迁移脚本，自动创建表并运行迁移；当前代码最新版本化迁移为 `V60__simplify_user_roles.sql`。

不需要逐个手工执行 `db/migration-mysql` 下的 SQL，不需要先导入 SQLite 文件，也不要执行重建脚本。

### 6.2 迁移当前 Windows 电脑的数据

如果要保留账号、密码、权限、存储配置和文件，执行本分支。

1. 安排停写窗口，停止旧环境后端，避免导出数据库时文件仍被修改。
2. 备份旧 MySQL 数据库，并保留 `flyway_schema_history`。
3. 备份实际文件目录；数据库本身不包含本地存储的文件内容。
4. 导入 Ubuntu 上新建的空数据库，复制文件到数据目录。
5. 启动新后端，修改存储路径、域名和回调地址，完成验收后再切换用户入口。
6. 验收前保留旧环境和备份；迁移期间只允许一端写入。

Windows PowerShell 导出示例，需要 `mysqldump` 已加入 PATH：

```powershell
Set-Location 'C:\Users\31717\Desktop\yun\student-cloud-github'
New-Item -ItemType Directory -Force .\server-migration | Out-Null

mysqldump -h 127.0.0.1 -P 3306 -u root -p `
  --single-transaction --routines --triggers --events `
  --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 `
  --result-file=.\server-migration\student_cloud.sql student_cloud
if ($LASTEXITCODE -ne 0) { throw '数据库导出失败，不能继续迁移。' }
```

使用 `--result-file`，避免旧版 Windows PowerShell 的 `>` 重定向产生不适合 MySQL 导入的文本编码。此处没有使用 `--databases`，便于以后恢复到不同名称的新库。

检查当前后台每个“本地存储”的真实根路径。若确实使用工作区中的 `student-cloud-files`，可打包该目录的**内容**：

```powershell
tar -czf .\server-migration\storage.tar.gz -C .\student-cloud-files .
if ($LASTEXITCODE -ne 0) { throw '文件备份失败。' }
scp .\server-migration\student_cloud.sql .\server-migration\storage.tar.gz deploy@SERVER_IP:~/student-cloud-upload/20260920-001/
```

如果有多个本地存储目录，要逐个备份并明确恢复位置，不能只复制这个示例目录。分享链接、对象存储密钥、用户口令摘要等敏感信息可能存在于数据库中，传输用 SSH，备份文件必须限制权限。

Ubuntu 导入时，先确认后端尚未启动、目标库是空库：

```bash
chmod 600 ~/student-cloud-upload/20260920-001/student_cloud.sql
sudo mysql -e 'SHOW TABLES FROM student_cloud;'
# 只有确认这是本次新建的空库，才执行下面的导入。
sudo mysql student_cloud < ~/student-cloud-upload/20260920-001/student_cloud.sql
```

文件先以普通 SSH 用户解压，再复制到经过确认的目标目录：

```bash
cd ~/student-cloud-upload/20260920-001
mkdir storage-unpack
tar -xzf storage.tar.gz -C storage-unpack
# 目标目录应为本次迁移预留的空目录；不要向已有业务目录盲目覆盖。
sudo ls -la /var/lib/student-cloud/storage
sudo rsync -a --chown=studentcloud:studentcloud --chmod=D750,F640 \
  storage-unpack/ /var/lib/student-cloud/storage/
```

登录新后台后，将 Windows 路径（例如 `C:\...\student-cloud-files`）修改为 `/var/lib/student-cloud/storage`。如果迁移多个存储，为每个存储配置对应的 Linux 子目录。

必须保留文件相对路径、文件名大小写和目录层级。Linux 区分大小写；只导入数据库却没复制真实文件，会出现配置存在但无法访问文件的情况。

已初始化的数据库继续使用原有管理员账号和密码，不要重新运行安装。若来源是 SQLite，本节 MySQL 导出命令不适用，需要另外设计数据转换并在副本验证，不能将 `.db` 文件直接导入 MySQL。

## 7. 配置生产后端

### 7.1 外部应用配置

```bash
sudo nano /etc/student-cloud/application.properties
```

粘贴以下内容，替换域名。`nano` 中按 `Ctrl+O`、回车保存，再按 `Ctrl+X` 退出。

```properties
server.address=127.0.0.1
server.port=8080
server.forward-headers-strategy=framework

spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/student_cloud?characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=student_cloud_app
spring.datasource.password=${STUDENT_CLOUD_DB_PASSWORD}
spring.datasource.hikari.maximum-pool-size=10

spring.flyway.enabled=true
spring.flyway.clean-disabled=true

tongligong-student-cloud.debug=false
tongligong-student-cloud.demo-site=false
tongligong-student-cloud.log.path=/var/log/student-cloud
tongligong-student-cloud.cors.allowed-origins=https://cloud.example.com

spring.servlet.multipart.max-file-size=512MB
spring.servlet.multipart.max-request-size=520MB

springdoc.swagger-ui.enabled=false
springdoc.api-docs.enabled=false
knife4j.enable=false
```

这里的 MySQL `useSSL=false` 只适用于**同机回环连接**。数据库迁往远程主机或托管数据库时，需按服务商要求配置 TLS、证书校验及网络限制，不要原样复制这一连接串。

本例将 multipart 请求总大小设为 `520MB`，为 `512MB` 单文件预留表单开销；第 9 节 Nginx 限制也对应 `520m`。这是请求上限示例，并不表示各种存储驱动都支持该大小。

不要启用 `local` profile，它是项目的 SQLite 本地演示配置。生产启动命令会明确指定 `prod`。

外部配置采用 `spring.config.additional-location`，在保留 JAR 内必需配置的同时覆盖生产参数；不要随意改成完全替换搜索路径的 `spring.config.location`。[Spring Boot 外部配置说明](https://docs.spring.io/spring-boot/3.3/reference/features/external-config.html)

### 7.2 密钥文件

```bash
sudo nano /etc/student-cloud/secrets.env
```

文件内容：

```dotenv
STUDENT_CLOUD_DB_PASSWORD=REPLACE_WITH_RANDOM_DATABASE_PASSWORD
TLGXY_CLOUD_AI_ENABLED=false
```

数据库密码必须与第 5 节一致。使用上面生成的十六进制随机密码，可以避免配置文件中引号、反斜杠、`$` 等特殊字符转义问题。

该文件是 systemd 的环境文件，不是 shell 脚本：不要写 `export`，不要写命令替换或依赖其他变量展开。Agent 稍后按第 12 节开启。

```bash
sudo chown root:studentcloud /etc/student-cloud/application.properties
sudo chmod 0640 /etc/student-cloud/application.properties
sudo chown root:root /etc/student-cloud/secrets.env
sudo chmod 0600 /etc/student-cloud/secrets.env
```

systemd 由系统管理器读取环境文件，再将所需变量传给服务；不需要把密钥放进启动参数、前端 JSON 或公开仓库。

## 8. 使用 systemd 管理后端

### 8.1 创建服务

```bash
sudo nano /etc/systemd/system/student-cloud.service
```

粘贴：

```ini
[Unit]
Description=Student Cloud Backend
Wants=network-online.target mysql.service
After=network-online.target mysql.service
StartLimitIntervalSec=120
StartLimitBurst=5

[Service]
Type=simple
User=studentcloud
Group=studentcloud
WorkingDirectory=/var/lib/student-cloud
EnvironmentFile=/etc/student-cloud/secrets.env
Environment=TZ=Asia/Shanghai
ExecStart=/usr/bin/java -Xms256m -Xmx1024m -XX:+ExitOnOutOfMemoryError -Duser.home=/var/lib/student-cloud -Djava.io.tmpdir=/var/lib/student-cloud/tmp -Dfile.encoding=UTF-8 -jar /opt/student-cloud/current/app.jar --spring.profiles.active=prod --spring.config.additional-location=file:/etc/student-cloud/
Restart=on-failure
RestartSec=10
TimeoutStopSec=90
SuccessExitStatus=143
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/student-cloud /var/log/student-cloud
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

`-Xmx1024m` 是 4 GB 单机方案的起步配置，不是整个 Java 进程的总内存上限；还要留出 MySQL、系统和 Java 非堆内存空间。

此服务只允许写入数据目录和应用日志目录。若以后把文件放到 `/data/student-cloud`，必须同时设置目录属主，并将该路径加入 `ReadWritePaths`；不能用 `chmod -R 777` 或以 root 运行来绕过问题。`ProtectHome=true` 也意味着不能把业务文件放在 `/home/deploy`。[systemd 文件系统保护说明](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml)

### 8.2 启动与检查

```bash
sudo systemd-analyze verify /etc/systemd/system/student-cloud.service
sudo systemctl daemon-reload
sudo systemctl enable --now student-cloud
sudo systemctl status student-cloud --no-pager
sudo journalctl -u student-cloud -n 120 --no-pager
sudo ss -ltnp | grep ':8080'
curl -fsS http://127.0.0.1:8080/api/install/status
```

首次启动需要等待 Flyway 执行。`systemctl` 显示 `active` 只代表进程在运行，不代表数据库迁移和业务已就绪，必须继续检查日志和接口。

状态接口应返回 JSON，业务 `code` 为 `0`：

- `data: false`：全新数据库还没有完成网页初始化，正常。
- `data: true`：已安装；若迁移了旧数据库，继续使用旧账号。
- HTTP 500 或数据库异常：先排查第 17 节，不要反复提交安装请求。

检查数据库迁移记录：

```bash
sudo mysql student_cloud -e \
  'SELECT installed_rank,version,description,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;'
```

当前版本应存在成功的 V60 记录，且不应出现失败迁移。不要为了通过启动而删除迁移历史、随意 `repair` 或关闭 Flyway。

## 9. 配置 Nginx 和 HTTPS

### 9.1 先只开放证书验证，不暴露安装页

先创建证书验证目录：

```bash
sudo install -d -o root -g root -m 0755 /var/www/letsencrypt/.well-known/acme-challenge
sudo nano /etc/nginx/sites-available/student-cloud
```

首次写入以下临时配置：

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name cloud.example.com;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/letsencrypt;
        try_files $uri =404;
    }

    location / {
        return 503;
    }
}
```

启用本站点；如果同名链接已经存在，先检查它，不要强行覆盖：

```bash
sudo ln -s /etc/nginx/sites-available/student-cloud /etc/nginx/sites-enabled/student-cloud
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
dig +short cloud.example.com A
dig +short cloud.example.com AAAA
```

不要删除其他站点的配置。已有同域名 `server_name` 配置时应先消除冲突。

### 9.2 申请证书

确认域名解析正确、公网 80 端口可达，再执行：

```bash
sudo certbot certonly --webroot -w /var/www/letsencrypt \
  -d cloud.example.com --email admin@example.com --agree-tos
sudo certbot certificates
```

本文使用 webroot 模式，让 Certbot 只申请证书，不自动重写复杂的代理配置。证书路径以 `certbot certificates` 输出为准；若已有同名证书，目录可能带编号。[Certbot webroot 说明](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot)

没有真实域名时，先用 SSH 隧道或受限制的内网环境验证。本文证书步骤针对域名，不要把 `SERVER_IP`、`localhost` 或示例域名当作可直接申请的真实域名。

### 9.3 管理页面与管理 API 的分流

**项目特别注意：** `/admin/user-list` 是前端页面，但 `/admin/storages` 等是后端接口。不能把整个 `/admin/` 一律转发后端，也不能一律返回前端页面。

创建 `/etc/nginx/conf.d/student-cloud-map.conf`：

```bash
sudo nano /etc/nginx/conf.d/student-cloud-map.conf
```

内容如下。`map`、`log_format` 必须位于 Nginx 的 `http` 上下文，Ubuntu 默认的 `conf.d/*.conf` 加载位置满足这一点；不要把它们粘进 `server` 或 `location`：

```nginx
map "$request_method:$http_accept" $student_cloud_admin_navigation {
    default 0;
    ~*^(GET|HEAD):.*text/html 1;
}

# 默认不记录请求路径、查询参数、Referer，减少分享令牌/SSO token 写入访问日志。
log_format student_cloud_minimal '$remote_addr [$time_local] '
    '"$request_method" $status $body_bytes_sent $request_time';
```

创建公共代理片段 `/etc/nginx/snippets/student-cloud-proxy.conf`：

```bash
sudo nano /etc/nginx/snippets/student-cloud-proxy.conf
```

```nginx
proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header Forwarded "";
proxy_set_header X-Forwarded-Prefix "";

proxy_connect_timeout 10s;
proxy_send_timeout 600s;
proxy_read_timeout 600s;
proxy_request_buffering off;
proxy_buffering off;
proxy_cache off;
proxy_intercept_errors off;
```

这是“公网直接到本机 Nginx”的配置，转发头会被覆盖，避免直接信任用户伪造的代理地址。若前面还有 CDN 或负载均衡，需要只信任其明确的代理网段并调整真实 IP 处理，不能直接相信任意客户端的 `X-Forwarded-For`。

### 9.4 替换为完整站点配置

再次编辑 `/etc/nginx/sites-available/student-cloud`，用下面完整内容替换临时配置；替换域名、证书路径和管理员公网 IP：

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name cloud.example.com;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/letsencrypt;
        try_files $uri =404;
    }

    location / {
        return 301 https://cloud.example.com$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name cloud.example.com;

    ssl_certificate /etc/letsencrypt/live/cloud.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/cloud.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    root /opt/student-cloud/current/frontend;
    index index.html;
    server_tokens off;
    client_max_body_size 520m;
    client_body_timeout 600s;
    send_timeout 600s;

    access_log /var/log/nginx/student-cloud.access.log student_cloud_minimal;
    error_log /var/log/nginx/student-cloud.error.log warn;

    # 首次安装期间：仅允许本机和管理员出口 IP。
    # 若管理员使用 IPv6，还需添加真实的管理员 IPv6 地址。
    allow 127.0.0.1;
    allow ::1;
    allow 203.0.113.10;
    deny all;

    location = /index.html {
        add_header Cache-Control "no-store" always;
        try_files $uri =404;
    }

    location = /student-cloud.config.json {
        add_header Cache-Control "no-store" always;
        try_files $uri =404;
    }

    location ^~ /assets/ {
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }

    # 精确 /sso 是前端登录回调页，/sso/{provider}/... 才是后端。
    location = /sso {
        try_files /index.html =404;
        add_header Cache-Control "no-store" always;
    }

    location = /file {
        try_files /index.html =404;
        add_header Cache-Control "no-store" always;
    }

    location ~ ^/admin(/|$) {
        error_page 418 =200 /index.html;
        if ($student_cloud_admin_navigation) {
            return 418;
        }
        include /etc/nginx/snippets/student-cloud-proxy.conf;
        proxy_pass http://127.0.0.1:8080;
    }

    location ~ ^/(api|user|file|pd|d|s|sso|onlyOffice|onedrive|onedirve|gd|s3|sharepoint|115)(/|$) {
        include /etc/nginx/snippets/student-cloud-proxy.conf;
        proxy_pass http://127.0.0.1:8080;
    }

    location ~ /\. {
        deny all;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

说明：

- `/admin` 通过 GET/HEAD 和 `Accept: text/html` 判断浏览器页面导航，行为与项目开发代理一致。API 的认证授权仍由后端负责，Nginx 路由不是权限校验。
- API、文件下载、短链、OAuth、OnlyOffice 都需转发，不能只代理 `/api`。`onedirve` 是当前代码保留的兼容路径，不是本文笔误。
- 正则 `location` 的 `proxy_pass` 不附加 `/` 等 URI，保留原请求路径。[Nginx proxy_pass 说明](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- SPA 回退只应用于页面路径；不要将所有后端 404/500 都改成 `index.html`，否则前端收到 HTML 后会出现难以定位的错误。
- 不将真实存储目录配置成 Nginx 的公开 `root` 或 `alias`，避免绕过后端鉴权。
- 应用的路径前缀是保留入口，存储别名不要选 `api`、`admin`、`user`、`assets`、`sso` 等冲突名称。

校验后再加载：

```bash
sudo nginx -t
# 只有上一条通过，才执行 reload。
sudo systemctl reload nginx
curl --resolve cloud.example.com:443:127.0.0.1 \
  -fsS https://cloud.example.com/api/install/status
```

若服务器本身没有 IPv6，按实际系统情况去掉两条 `[::]` 监听，并确保 DNS 没有错误的 AAAA。

### 9.5 自动续期

创建续期成功后的 Nginx 重载钩子：

```bash
sudo install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
sudo nano /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
```

文件内容：

```sh
#!/bin/sh
set -eu
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
```

然后执行：

```bash
sudo chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
sudo systemctl enable --now certbot.timer
sudo systemctl list-timers --all | grep certbot
sudo certbot renew --dry-run
sudo /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
```

本文使用 apt 安装的 Certbot，定时器名称按该安装方式检查；已有 snap 或其他安装方式时，不要混装，先核实现有续期机制。保留 HTTP 的 ACME 验证路径，否则续期可能失败。

## 10. 完成首次安装和三类角色配置

保持 Nginx 管理员 IP 白名单，使用浏览器打开：

```text
https://cloud.example.com/install
```

### 10.1 全新数据库

填写站点名称、系统管理员账号和强密码。生产环境不要使用之前调试时的简单密码，也不存在必须沿用的通用默认管理员密码。

完成后访问 `/login` 登录，并确认 `/api/install/status` 的 `data` 为 `true`。

### 10.2 迁移数据库

已初始化的数据库直接访问 `/login`，使用迁移前的账号、密码。不要在安装页反复创建管理员。

### 10.3 角色与访客权限

当前系统只保留以下三类角色：

| 角色 | 标识 | 说明 |
| --- | --- | --- |
| 系统管理员 | `ADMIN` | 系统配置、用户和存储管理 |
| 普通用户 | `USER` | 按授权访问文件和功能，不能调用管理员接口 |
| 访客 | `GUEST` | 未登录身份，只能访问显式授予访客的内容 |

数据库中的 `id=0` 是“新用户权限模板”，不是第四类角色或可登录账号；`id=2` 是访客，不应设置为普通登录账户。V60 会将历史角色归一化，保留用户和存储授权。

首次上线建议关闭匿名写入，谨慎开放访客下载、上传、删除和分享权限。新建普通用户后应单独验证其存储范围。

## 11. 配置本地存储、站点域名和外部服务

### 11.1 本地存储

在管理员后台的存储管理中新增或编辑本地存储：

| 配置项 | 示例 |
| --- | --- |
| 类型 | 本地存储 |
| 名称 | 学生云盘文件 |
| 存储别名 | `campus`，避免使用系统保留路由 |
| 根路径 | `/var/lib/student-cloud/storage` |
| 私有访问/代理鉴权 | 涉及私有文件时启用相应鉴权配置 |
| 文件操作与用户授权 | 按实际需要分别配置，先最小权限 |

检查运行账户是否能读写：

```bash
sudo -u studentcloud test -r /var/lib/student-cloud/storage
sudo -u studentcloud test -w /var/lib/student-cloud/storage
sudo -u studentcloud test -x /var/lib/student-cloud/storage
namei -l /var/lib/student-cloud/storage
df -h /var/lib/student-cloud/storage
```

`test` 成功时通常没有输出；失败时退出码非 0。若使用数据盘，先确认磁盘已正确挂载并设置开机挂载，再让后端使用该路径，避免磁盘未挂载时把数据写入系统盘。同样要更新 systemd 的 `ReadWritePaths`，必要时为服务增加 `RequiresMountsFor=/data/student-cloud`。

### 11.2 域名设置

在后台相应站点设置中，将“前端域名”和“强制后端地址”配置为：

```text
https://cloud.example.com
```

对应项目配置字段为 `frontDomain`、`forceBackendAddress`。不要保留 Windows 环境的 `127.0.0.1`、`localhost` 或测试端口，否则分享、下载、SSO 跳转可能指向错误机器。

### 11.3 第三方能力

- OneDrive、Google Drive、SharePoint、S3 等按自己的应用和账号配置密钥、回调地址与权限，不使用源码中的默认 OAuth 凭据作为生产凭据。
- SSO 需在身份提供方登记本站 HTTPS 回调地址；修改域名后同时更新提供方配置和项目配置。
- OnlyOffice 需要独立 Document Server、正确的双向访问地址及鉴权配置，本项目后端启动不代表 OnlyOffice 已部署。
- 远程对象存储数据不在本机 `storage` 备份中，应另外配置存储服务自身的版本控制和备份。
- 同域 Nginx 解决了网站 API 的来源问题，但云存储直传、第三方预览仍可能有自己的 CORS 配置，不要因此在全站添加任意来源通配放行。

第三方集成不在本文基础部署的自动验收范围内，启用哪一项就补测哪一项。

## 12. 配置 AI Agent 与知识库

### 12.1 Agent 的部署位置

Agent 是当前 Java 后端的一部分，不需要额外启动 Python 服务或新增公网端口。主要接口为 `/api/ai/chat` 和 `/api/ai/chat/files`，现有 Nginx 规则已经覆盖。

当前知识检索配置使用 JAR 内的管理员/普通用户知识 JSON；基础运行不需要另外部署向量数据库。核心文件在源码中为：

```text
backend/src/main/resources/ai-agent.properties
backend/src/main/resources/ai-rag.properties
backend/src/main/resources/ai/knowledge/admin-knowledge.json
backend/src/main/resources/ai/knowledge/user-knowledge.json
```

### 12.2 启用真实模型

先确认所使用模型提供商的账户、余额、模型 ID 和接口兼容性。编辑 `/etc/student-cloud/secrets.env`，保留数据库密码，将 Agent 部分改为：

```dotenv
TLGXY_CLOUD_AI_ENABLED=true
TLGXY_CLOUD_AI_API_KEY=REPLACE_WITH_YOUR_MODEL_API_KEY
TLGXY_CLOUD_AI_BASE_URL=https://api.deepseek.com
TLGXY_CLOUD_AI_MODEL=REPLACE_WITH_AVAILABLE_MODEL_ID
TLGXY_CLOUD_AI_TIMEOUT_SECONDS=45
TLGXY_CLOUD_AI_MAX_HISTORY=8
TLGXY_CLOUD_AI_MAX_TOOL_ROUNDS=4
TLGXY_CLOUD_AI_RAG_ENABLED=true
TLGXY_CLOUD_AI_RAG_TOP_K=4
TLGXY_CLOUD_AI_RAG_MIN_SCORE=20
TLGXY_CLOUD_AI_RAG_MAX_CONTEXT_CHARS=5000
```

示例 URL 对应项目默认提供商配置；请填写你实际有权限调用的模型，而不是假定源码默认模型名在你的账户一定可用。其他服务商需核对项目当前调用协议后再替换。

源码同时支持 `DEEPSEEK_API_KEY`，且其优先级高于 `TLGXY_CLOUD_AI_API_KEY`。建议只设置其中一种，避免旧的 `DEEPSEEK_API_KEY` 覆盖新密钥。不要在 `public/student-cloud.config.json` 或前端环境变量中放模型密钥。

修改后重启：

```bash
sudo systemctl restart student-cloud
sudo journalctl -u student-cloud -n 80 --no-pager
```

### 12.3 Agent 验收和隐私

- 系统管理员、普通用户分别提问，检查返回内容和可用工具是否符合各自权限。
- 未登录访客不应成功调用需要登录的 Agent 接口。
- 上传一个不含隐私的测试附件，验证附件解析、问答和错误提示。
- 禁用 Agent、密钥缺失、模型不可用时，应该有明确错误，不能影响普通文件操作。
- 检查服务器到模型 API 的出站 HTTPS、DNS 和证书验证，不要通过关闭 TLS 校验处理连接问题。
- 对话、附件文本或工具结果可能进入第三方模型请求，启用前确认数据使用许可；不要用真实学生隐私、密码或密钥做测试。
- 模型调用可能收费，设置预算和监控；基础程序启动成功不等于真实模型已调用成功。

若暂时不用 AI，保留 `TLGXY_CLOUD_AI_ENABLED=false`，待普通云盘功能验收后再启用。

## 13. 上线验收清单

### 13.1 命令行检查

在服务器执行。以下以已完成初始化为前提：

```bash
sudo systemctl is-active mysql nginx student-cloud
sudo ss -ltnp | grep -E ':(80|443|8080|3306)\b'

# 后端存活及初始化状态。
curl -fsS http://127.0.0.1:8080/api/install/status | jq .

# HTTPS 代理状态；本机回环访问仍带真实域名并验证证书。
curl --resolve cloud.example.com:443:127.0.0.1 \
  -fsS https://cloud.example.com/api/install/status | jq .

# 管理页面刷新必须是 HTML 页面，不应由后端返回 JSON/404。
curl --resolve cloud.example.com:443:127.0.0.1 \
  -sS -D - -o /dev/null -H 'Accept: text/html' \
  https://cloud.example.com/admin/user-list

# 管理接口不带登录令牌时必须拒绝；不能错误地返回 HTML 首页。
curl --resolve cloud.example.com:443:127.0.0.1 \
  -sS -i -H 'Accept: application/json' https://cloud.example.com/admin/storages
```

注意：本项目部分错误以 JSON 业务码表达，不能只看 HTTP 200；同时检查 JSON 的 `code`、消息和数据。`/api/install/status` 只用于本次基础检查，不是全面健康检查接口，也不证明上传、权限或外部模型正常。

### 13.2 浏览器验收

使用专门测试用户、测试存储目录和无敏感内容的测试文件；删除测试只针对测试文件，不要操作真实业务数据。

| 类别 | 必测内容 |
| --- | --- |
| 页面 | 首页、登录页、管理页直接打开与刷新正常，JS/CSS 无 404 |
| 初始化 | 安装后状态为 true，不允许再次覆盖创建管理员 |
| 管理员 | 登录、退出、用户管理、存储管理和站点设置正常 |
| 普通用户 | 只能访问获授权存储；访问管理页面及管理 API 均不能越权 |
| 访客 | 只能看到显式公开内容，不能登录访客模板账户，不能访问私有文件 |
| 文件操作 | 测试目录中新建、上传、列表、下载、重命名、移动、删除 |
| 文件完整性 | 上传前与下载后校验 SHA256；覆盖中文、空格和大小写文件名 |
| 大文件 | 用符合容量限制的测试文件检查超时、413、临时目录及磁盘占用 |
| 预览 | 图片、文本、PDF、音视频等已配置类型；拖动媒体进度检查 Range |
| 分享与直链 | HTTPS 域名正确，密码/有效期/私有访问限制生效 |
| Agent | 按第 12 节分别验证角色、附件、失败路径与真实模型 |
| 第三方 | 按已启用的 SSO、对象存储、OnlyOffice 分别补测 |
| 运维 | 服务重启后数据仍在、能重新登录，证书续期演练通过 |
| 灾备 | 至少一次恢复到隔离新库和临时文件目录并验证可用 |

内存会话在重启后可能失效，需要重新登录；这不等于用户账号丢失。

当前工作区的既有测试记录请看《项目测试报告.md》。本次只是编写部署文档，没有替你执行上述服务器验收，不应将历史本机测试当作生产验证。

文档检查范围：示例中 5 个 PowerShell 代码块通过 PowerShell 语法解析，35 个 Bash/sh 代码块通过 Bash `-n` 语法检查；这些检查没有执行部署命令。Nginx、systemd、证书、MySQL 权限和实际上传能力仍需在目标 Ubuntu 上验证。

### 13.3 解除安装白名单

确认管理员已初始化、强密码已设置、私有文件权限正确、验收通过之后，再编辑 Nginx HTTPS 的 `server`，删除第 9.4 节的三条 `allow` 和一条 `deny all`。

只删除首次安装白名单那几行，不要删除用于隐藏文件保护的 `location ~ /\.`。若网站仅供内网或固定组织访问，可以继续保留访问白名单。

```bash
sudo nginx -t
sudo systemctl reload nginx
```

然后从其他网络以无痕窗口验证访客行为。尚未完成安装时，不要把 `/install` 和 `/api/install` 无保护暴露到公网。

## 14. 备份与恢复

### 14.1 必须备份哪些内容

- MySQL 业务库，包括用户、权限、存储参数、系统设置和 Flyway 记录。
- 所有实际本地文件目录；不只是本文示例的一个目录。
- `/etc/student-cloud`，其中含密钥，必须加密或严格限制访问。
- Nginx 站点、map、代理片段、systemd 服务和当前版本编号。
- 外部对象存储、SSO、证书私钥等按各自的灾备方案单独处理。

备份副本不能只留在同一块服务器磁盘。建议按可接受的数据损失窗口安排周期，备份后校验并复制到受控异机；没有恢复演练的备份不能视为已验证。

### 14.2 一次停写一致性备份

以下备份适合本文单实例、本地存储方案。会临时停止后端，安排维护窗口；Nginx 此时可能返回 502。外部写入工具和其他应用也必须停止写入对应文件或数据库。

先创建脚本：

```bash
sudo nano /usr/local/sbin/student-cloud-backup
```

脚本内容：

```bash
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if [[ $EUID -ne 0 ]]; then
    echo 'Please run with sudo.' >&2
    exit 1
fi

BACKUP_DIR="/var/backups/student-cloud/$(date +%Y%m%d-%H%M%S)"
mkdir -m 0700 "$BACKUP_DIR"

WAS_ACTIVE=0
if systemctl is-active --quiet student-cloud; then
    WAS_ACTIVE=1
fi

cleanup() {
    local result=$?
    if [[ "$WAS_ACTIVE" == 1 ]]; then
        systemctl start student-cloud || result=1
    fi
    if [[ "$result" != 0 ]]; then
        echo "Backup or restart failed; inspect $BACKUP_DIR and service status." >&2
    fi
    exit "$result"
}
trap cleanup EXIT

systemctl stop student-cloud
mysqldump --single-transaction --routines --triggers --events \
    --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 \
    student_cloud > "$BACKUP_DIR/database.sql"
test -s "$BACKUP_DIR/database.sql"

tar -C /var/lib/student-cloud -czf "$BACKUP_DIR/storage.tar.gz" storage
tar -C / -czf "$BACKUP_DIR/config.tar.gz" \
    etc/student-cloud \
    etc/nginx/sites-available/student-cloud \
    etc/nginx/conf.d/student-cloud-map.conf \
    etc/nginx/snippets/student-cloud-proxy.conf \
    etc/systemd/system/student-cloud.service
readlink -f /opt/student-cloud/current > "$BACKUP_DIR/release.txt"

cd "$BACKUP_DIR"
sha256sum database.sql storage.tar.gz config.tar.gz release.txt > SHA256SUMS
echo "Backup saved: $BACKUP_DIR"
```

执行：

```bash
sudo chmod 0700 /usr/local/sbin/student-cloud-backup
sudo /usr/local/sbin/student-cloud-backup
sudo systemctl status student-cloud --no-pager
curl -fsS http://127.0.0.1:8080/api/install/status
```

脚本使用 Ubuntu 常见的 root 本地 socket 认证。若你修改过 MySQL root 认证方式，应使用权限为 `0600` 的 MySQL 客户端配置或其他受控凭据机制；不要把密码拼进命令行，也不要取消数据库认证。

脚本失败时目录可能是半成品，不能当作成功备份；检查退出状态、校验文件和服务恢复情况。脚本只包含一个 `storage` 目录，有其他存储路径时须扩充。证书和外部对象存储不在此脚本范围内。

### 14.3 恢复到隔离新库

先验证备份校验和。下面的时间戳为示例，替换成实际目录：

```bash
sudo bash -c 'cd /var/backups/student-cloud/20260920-150000 && sha256sum -c SHA256SUMS'
sudo env MYSQL_HISTFILE=/dev/null mysql
```

在 MySQL 中创建新恢复库，不覆盖原业务库：

```sql
CREATE DATABASE student_cloud_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON student_cloud_restore.* TO 'student_cloud_app'@'127.0.0.1';
EXIT;
```

导入（按本文脚本生成的 SQL 不含固定 `USE student_cloud`）：

```bash
sudo bash -c 'mysql student_cloud_restore < /var/backups/student-cloud/20260920-150000/database.sql'
sudo mysql student_cloud_restore -e 'SHOW TABLES;'
```

将文件恢复到独立目录、核对内容后，在隔离环境使用**与备份匹配的 JAR**、恢复库和恢复文件路径进行演练。不要让恢复验证实例访问正式文件目录，也不要误用生产 OAuth 回调或发送真实外部操作。

正式回滚时应停写：保留当前数据库和文件的副本，切换到经验证的恢复库、匹配文件快照和程序版本，更新外部数据库配置及后台存储路径，再启动验收。数据库与文件必须对应同一个备份时间点；回滚会舍弃备份之后的变更，需要业务确认。

禁止直接往正在运行的业务库导入 SQL，禁止为了恢复而先删掉唯一现存的数据副本。

## 15. 更新版本与回滚

### 15.1 发布新版本

1. 本地执行第 4 节全部验证并构建前后端；前后端应来自同一份代码版本。
2. 换新发布编号，例如 `20260921-001`，上传并安装到新的 release 目录，不覆盖旧目录。
3. 暂停用户写入，执行第 14 节备份，确认备份完整；记录 `readlink -f /opt/student-cloud/current`。
4. 确认新版本的数据库迁移是否向后兼容，并准备回滚路径。
5. 停止后端、原子切换 `current` 链接，再启动后端。

在维护窗口执行以下示例；此时新版本文件应已安装好：

```bash
set -e
test -f /opt/student-cloud/releases/20260921-001/app.jar
test -f /opt/student-cloud/releases/20260921-001/frontend/index.html
readlink -f /opt/student-cloud/current
test -L /opt/student-cloud/current
test ! -e /opt/student-cloud/current.next
test ! -L /opt/student-cloud/current.next

sudo systemctl stop student-cloud
sudo ln -s /opt/student-cloud/releases/20260921-001 /opt/student-cloud/current.next
sudo mv -Tf /opt/student-cloud/current.next /opt/student-cloud/current
sudo systemctl start student-cloud
sudo journalctl -u student-cloud -n 120 --no-pager
curl -fsS http://127.0.0.1:8080/api/install/status
```

若步骤失败，停止发布并检查服务状态；不要继续开放流量。Nginx 根目录通过链接读取，按本文配置切换后无需更改 root；若修改了 Nginx 配置，仍需 `nginx -t` 后 reload。

6. 检查迁移成功、角色权限、文件读写和 Agent，再恢复流量。
7. 提醒已有浏览器页面刷新。旧页面可能请求旧哈希资源；更新后测试浏览器刷新和懒加载页面，不要把 JS 404 也回退成 HTML。
8. 保留至少一个可用的旧版本和对应备份，确认保留期后再人工清理，不使用宽泛递归删除命令。

注意备份脚本会恢复原本正在运行的后端；升级时在整个维护窗口持续限制写入，避免“备份完成”到“切换版本”之间产生漏掉的新数据。

### 15.2 回滚条件

仅当前后版本数据库结构和数据语义都兼容时，才可以只将 `current` 切回旧 release 后重启。保持前后端一起切换。

**特别注意 V60：** 当前角色已归一化为 `ADMIN`、`USER`、`GUEST`。旧版本如果依赖 `SUPER_ADMIN`、`TENANT_ADMIN` 等角色，直接回退旧 JAR 可能无法正确登录或鉴权。不能认为“保留旧 JAR”就等于完整回滚方案。

迁移不兼容时，按第 14.3 节恢复匹配的数据库、文件、配置和程序版本。不要手工删除 V60 历史记录后指望 Flyway 自动撤销，也不要修改已在生产执行过的迁移脚本内容。

## 16. 日常运维命令

```bash
# 启停和重启。
sudo systemctl start student-cloud
sudo systemctl stop student-cloud
sudo systemctl restart student-cloud

# 查看状态与最近日志。
sudo systemctl status student-cloud --no-pager
sudo journalctl -u student-cloud -n 100 --no-pager
sudo journalctl -u student-cloud -f
sudo tail -n 100 /var/log/student-cloud/tongligong-student-cloud.log
sudo tail -n 100 /var/log/nginx/student-cloud.error.log

# 资源检查。
free -h
df -h
df -i
sudo du -sh /var/lib/student-cloud/storage /var/log/student-cloud /var/backups/student-cloud
sudo journalctl --disk-usage

# 配置文件或环境密钥变化后：重启后端。
sudo systemctl restart student-cloud

# 只有 systemd unit 文件变化时才需额外 daemon-reload。
sudo systemctl daemon-reload
sudo systemctl restart student-cloud
```

不要把整段“日常命令”一次性执行，它们是按需要选择的操作。关闭 SSH 不会停止 systemd 管理的服务。

应用滚动日志、Nginx 日志、journal 和备份都应设置容量与保留策略并监控剩余磁盘。即便访问日志省略了请求路径，错误日志或应用日志仍可能包含敏感链接；排错分享日志前应脱敏。

## 17. 常见故障排查

| 现象 | 优先检查 | 处理方向 |
| --- | --- | --- |
| Nginx 502 | 后端进程、8080 监听、启动日志 | 修复后端启动原因，不是先改前端 |
| Nginx 504 / Agent 超时 | 后端执行时间、模型出站网络、提供商响应 | 查真实耗时和错误，再合理调整超时 |
| 安装 POST `/api/install` 返回 500 | `journalctl`、数据库权限、Flyway、请求响应体 | 数据库连通和迁移成功后再安装；不要重复重建库 |
| `Access denied for user` | JDBC 用户、密码、MySQL Host 授权 | 用 `mysql -h 127.0.0.1 -u student_cloud_app -p` 验证 |
| `Communications link failure` | MySQL 服务、3306 回环监听、连接串 | 检查数据库服务；不要靠公网开放 3306 解决 |
| `Unknown database` | 外部 JDBC URL、目标库是否创建 | 核对库名；迁移时不要连错库 |
| Flyway 校验失败 | 当前 JAR 版本、历史 SQL 校验和、历史表 | 先备份并找出版本差异，不能直接删历史或乱 repair |
| 页面请求到了 `127.0.0.1:8080` | 前端配置 `baseUrl`、后台强制后端地址 | 同域前端 `baseUrl` 留空，后台地址设公网 HTTPS |
| `/admin/user-list` 刷新 404/JSON | Nginx map、Accept 分流、页面回退 | 按第 9 节分流，并用 `Accept: text/html` 检查 |
| 管理 API 返回 HTML | `/admin` 被一律当作前端路由 | API JSON 请求应进入后端，不要全局 SPA 错误回退 |
| 首页能开但页面空白 | 浏览器 Network、assets 路径、dist 完整性 | 上传 dist 内容而非额外套一层 dist；前后端同步发布 |
| 首次访问 403 | 安装白名单中的出口 IP | 检查真实出口 IPv4/IPv6；安装前不要随意移除白名单 |
| POST/PUT 403 | 来源校验、角色权限、登录状态 | 核对 HTTPS 域名的 CORS 配置及实际响应；不要把所有 403 当白名单问题 |
| 上传 413 | Nginx、Spring、CDN/负载均衡请求上限 | 全链路统一，并给 multipart 总大小预留开销 |
| 上传慢或中途断开 | 网络、磁盘空间、临时目录、超时 | 检查服务端日志、代理缓冲和各层超时，不盲目设无限制 |
| Linux `Permission denied` | 目录逐级权限、运行用户、systemd 保护 | 使用 `namei -l`，修复属主和精确可写路径 |
| 数据库有存储但文件不存在 | Windows 路径、文件是否复制、大小写 | 修改 Linux 根路径，核对真实文件与数据库一致性 |
| 下载链接是 HTTP 或 localhost | `frontDomain`、`forceBackendAddress`、转发头 | 统一 HTTPS 域名，检查 `server.forward-headers-strategy` |
| 证书申请/续期失败 | A/AAAA、80 端口、ACME 路径、CAA/CDN | 修复域名验证条件，执行 renew dry-run 验证 |
| Agent 不可用 | 开关、有效密钥、模型 ID、出站网络 | 检查第 12 节；前端能打开不能证明模型可调用 |
| Agent 仍在用旧密钥 | `DEEPSEEK_API_KEY` 优先级、是否重启 | 清理重复配置，修改环境文件后重启 |
| `UnsupportedClassVersionError` | 服务实际使用的 Java | `/usr/bin/java -version` 必须为 21 或与构建兼容的版本 |
| 服务反复重启 / OOM | journal、内存、堆配置、并发负载 | 先定位内存或启动错误，再调整资源；不要无限提高重启频率 |
| `Start request repeated too quickly` | 连续启动失败的根因 | 修复后 `sudo systemctl reset-failed student-cloud` 再启动 |

排错顺序建议：**后端日志 → 本机直连接口 → Nginx HTTPS 接口 → 浏览器 Network → 外部服务**。每一层确认正常后再看下一层。

## 18. 上线前最后核对

- [ ] 域名、邮箱、IP、版本号、数据库密码和模型配置占位符已替换。
- [ ] 服务只以 `studentcloud` 运行，8080/3306 未暴露公网。
- [ ] 数据库使用专用账户，未沿用本机 root 凭证。
- [ ] 管理员初始化完成，生产密码已更换，访客权限已限制。
- [ ] 前端 `baseUrl` 留空，站点前后端地址均为正确 HTTPS 域名。
- [ ] 本地存储路径是 Linux 路径，真实文件已迁移、权限和挂载正确。
- [ ] 管理页面刷新正常，管理 API 不会错误返回 HTML。
- [ ] Agent 密钥只在服务器受控配置中，真实模型已按需验收。
- [ ] 证书续期和重载钩子通过测试。
- [ ] 数据库、文件、配置有完整备份，至少做过一次隔离恢复演练。
- [ ] 更新回滚步骤已确认，V60 角色迁移兼容性已考虑。
- [ ] 第 13 节的生产验收已实际执行，并记录结果和未通过项目。

完成这些检查后，才可以把“服务已启动”升级为“本次部署已验收”。
