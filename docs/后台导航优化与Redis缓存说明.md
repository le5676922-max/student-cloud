# 后台导航优化与 Redis 缓存说明

更新：2026-10-09。适用于当前 Vue + Spring Boot + MySQL 项目。本次没有重建业务数据库、迁移若依框架、改动小芽形象或上传到 GitHub。

2026-10-10 补充：Redis 命中读路径已允许并发，并合并 payload/版本标记读取；等待数据库连接时不持有生命周期锁。新增目录快照是独立的进程内 5 秒缓存，不是 Redis 授权缓存。实现与测试见[业务优化实现与回归说明](业务优化实现与回归说明-20261010.md)。

## 1. 这次修复的卡顿

存储源页面的旧图标导入使用了当前 Vite 已不支持的 `import.meta.globEager`。原加载逻辑在网络、数据处理或拖拽初始化报错时也没有结束加载状态，因此出现整页遮罩持续转圈。

现在图标在模块加载时统一处理；存储列表的加载状态、筛选和拖拽实例属于当前页面，离开页面会清理。失败显示“重新加载”，12 秒仍未完成的请求会结束等待，旧响应不能覆盖新页面。加载提示不再遮住整个页面。

后台布局不再在路由鉴权之后重复调用登录检查，也不再每次进入布局就加载所有站点设置。每次受保护路由仍执行最新身份校验；导航检查最长等待 10 秒，快速切换取消旧检查。增加顶部进度条和页面模块加载失败提示。

上传、下载文件和 AI 操作没有套用 12 秒的管理元数据超时。

## 2. 哪些内容使用缓存

| 数据 | 策略 | 边界 |
| --- | --- | --- |
| 全局存储源展示列表 | 可选 Redis，默认内存；默认 TTL 120 秒 | 只存显式允许的展示字段；不存连接密码、SMTP 或模型密钥 |
| 现有站点配置等 Spring Cache | 保持进程内缓存 | 不把包含凭据的系统配置整体搬进 Redis |
| 同一次 GET/HEAD 请求的当前账号实体 | 请求内去重 | 不跨请求保存角色、启用状态或授权结论 |
| 账号/空间是否可用、角色及业务权限 | 继续实时校验 | 下次请求立即读取最新权限；不以 Redis 命中代替鉴权 |
| 文件内容、RAG 私有资料及 Agent 回答 | 未新增共享缓存 | 不按相同问题在不同用户间复用答案 |

MySQL 仍是业务数据的唯一来源。Redis 缓存命中减少存储列表查询，不意味着所有接口都不查询数据库，也不保证外部云盘或模型响应变快。

## 3. 本机启动 Redis

前提：Docker 已运行。在项目根目录执行：

```powershell
# 创建独立缓存服务，仅本机可以访问 16379，不占用 MySQL 端口。
docker compose -f docker/compose.cache.yml up -d

# PONG 表示缓存服务正常。
docker compose -f docker/compose.cache.yml exec redis-cache redis-cli ping
```

下面的环境变量只需配置在**后端进程**中，前端无需 Redis 配置：

```powershell
# 此开关与登录会话使用哪个存储是独立的，不会自动切换 Sa-Token 会话。
$env:STUDENT_CLOUD_CACHE_TYPE = 'redis'
$env:STUDENT_CLOUD_CACHE_NAMESPACE = 'student-cloud-local'
$env:STUDENT_CLOUD_CACHE_TTL = '120s'
$env:STUDENT_CLOUD_REDIS_HOST = '127.0.0.1'
$env:STUDENT_CLOUD_REDIS_PORT = '16379'
$env:STUDENT_CLOUD_REDIS_DATABASE = '1'
$env:STUDENT_CLOUD_REDIS_TIMEOUT = '800ms'

# 在 backend 目录按原方式启动，保留自己原来的 MySQL、邮箱等配置。
# Java 与 Maven 均使用项目要求的 Java 21 环境。
mvn spring-boot:run
```

如使用 IDE，将相同变量填写到 Spring Boot 运行配置的“环境变量”，然后重新启动后端。不要把 Redis、MySQL 或邮箱密码放到前端 `.env` 中。

`compose.cache.yml` 的无密码例子仅供受信任的本机使用，且未开放公网。生产使用受控网络、Redis ACL/密码；不要在阿里云安全组开放 6379 或 16379。绑定 localhost 会限制发布端口的访问范围，详见 [Docker 官方端口说明](https://docs.docker.com/engine/network/port-publishing/)。

## 4. 后端也部署到 Docker 时

容器中的 `127.0.0.1` 指的是该容器自身，不是 Redis 容器。把后端和 Redis 放进同一个 Compose 项目网络；后端配置如下：

```yaml
# 合并到你已有的后端 service 的 environment，不要替换原 MySQL/邮箱变量。
environment:
  STUDENT_CLOUD_CACHE_TYPE: redis
  STUDENT_CLOUD_CACHE_NAMESPACE: student-cloud-production
  STUDENT_CLOUD_CACHE_TTL: 120s
  STUDENT_CLOUD_REDIS_HOST: redis-cache
  STUDENT_CLOUD_REDIS_PORT: 6379
  STUDENT_CLOUD_REDIS_DATABASE: 1
  STUDENT_CLOUD_REDIS_TIMEOUT: 800ms
  # 真实密码只放在服务器受限权限的环境文件中。
  STUDENT_CLOUD_REDIS_PASSWORD: ${STUDENT_CLOUD_REDIS_PASSWORD}
```
同时为 Redis 配置匹配的 ACL/密码，并取消 Redis 的 `ports`（同一 Docker 网络使用内部 6379 即可）。本文件只提供缓存配置增量，不替代完整 Nginx、MySQL、文件持久化及 HTTPS 部署方案。

## 5. 修改后失效与故障处理

缓存 key 使用版本、环境命名空间与数据库标识摘要隔离。缓存仅保存短期的列表快照，更新、排序、删除等操作在修改前及事务提交后换代；旧查询不能重新写入当前代，旧 key 到期自然清理。无需清空整台 Redis，也不使用 `FLUSHDB` 或 `FLUSHALL`。

Redis 读取失败会回源 MySQL，并暂停本进程读取 Redis。恢复连接后，需要成功的缓存换代或重启后端才能恢复该进程的缓存读取，避免重连时读取过期状态。

在显式 `redis` 模式下，存储元数据修改需要成功换代：如果修改前无法失效，本次修改不执行；如果事务已提交后的失效失败，提示“数据已保存，但缓存刷新失败”，不要重复提交，应恢复 Redis 并刷新确认。如果希望 Redis 故障期间仍修改存储配置，可先将 `STUDENT_CLOUD_CACHE_TYPE=memory` 并重启**当前单实例**后端。不要混用共享 Redis 模式与多个独立内存实例。

此模式是缓存，不存登录会话和不可丢弃的数据，因此独立 Redis 容器关闭持久化，并设置内存上限及缓存淘汰策略。策略解释见 [Redis 官方淘汰说明](https://redis.io/docs/latest/develop/reference/eviction/)。

## 6. 验证与限制

前端覆盖正常加载、503 后重试、12 秒超时、离页取消、快速菜单切换、旧身份响应隔离、筛选禁止拖排和排序保存失败回滚。最终独立示例接口的热态存储页面切换中位数为 253 ms；重启 Vite 后首次冷加载约 5.4 秒，后续新浏览器上下文约 1.3–1.5 秒。这不是实际阿里云服务器、真实存储供应商的耗时，不能据此计算全站提速百分比，也不承诺所有冷访问秒开。

本轮验收结果：

- 前端单元回归：201 项通过；Vue 类型检查、改动范围 ESLint、最终生产构建通过。
- 后端完整回归：使用本轮新建的 3 个隔离 MySQL 测试库，473 项中 468 项通过、5 项跳过、0 失败、0 错误；完成后仅删除本轮创建的临时测试库，业务 `student_cloud` 未修改。
- 跳过的 5 项分别是 3 个未启用的真实模型调用、1 个未启用的性能基准和 1 个当前 Windows 环境不支持的符号链接用例。未把跳过项列为通过。
- 缓存专项 22 项通过，其中真实 Redis 5 项覆盖缓存命中、TTL、换代失效、旧查询并发隔离、命名空间及客户端故障。测试用随机 namespace 和 Redis DB15，只清理本次测试创建的 key。
- 认证与权限专项 35 项通过，其中新增请求内去重 17 项；同一个只读 HTTP 请求中的当前用户实体查询由 3 次变为 1 次，跨请求停用、降权仍重新校验。
- 独立浏览器 8 个后台交互场景通过，存储图标真实加载，0 页面运行时错误、0 遗留 API 请求、0 真实业务写入；后续的“数据已提交但缓存失效失败”提示增加了单元回归，只做 GET 重新确认，不自动重发修改。
- 真实本机站点的 5 项只读验证通过：匿名首页进入登录、受保护页面跳转登录、业务接口返回 401；没有登录测试账号或修改真实数据。

Windows 曾因运行中的旧 JAR 占用导致 `repackage` 失败；在确认全部测试通过并精确停止本项目旧 PID 后，重新打包成功。这是文件占用问题，不隐藏该首次打包失败记录。旧 JAR 留有可恢复副本，业务配置保留。

最终本机服务：前端 `http://127.0.0.1:5173`、MySQL 后端 `http://127.0.0.1:8080`、Compose Redis `127.0.0.1:16379`（healthy）。当前后端显式启用 Redis 展示元数据缓存，仍沿用原预览的 Flyway 禁用参数，未执行真实业务库迁移。重启后需要重新登录。Redis 不应配置到前端或开放公网。

完整日志及浏览器原始报告保存在被 Git 忽略的 `.codex-run`；没有把凭据、用户文件、测试账号或构建产物提交到版本库。此次未推送 GitHub。

Redis 不会自动把现有本地目录缓存、限流、任务执行、存储连接实例、权限失效机制全部变成分布式。因此当前仍按**单后端实例**部署，不宣称完成多实例高可用。管理员的全局菜单沿用现有业务边界，参考 [若依官方项目](https://github.com/yangzongzhuan/RuoYi-Vue) 的分组管理方式；没有引入它的账号库、数据库脚本或重复的空间管理功能。
