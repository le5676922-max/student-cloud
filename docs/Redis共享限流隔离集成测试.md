# Redis 共享限流隔离集成测试

本轮已补充真实 Redis 集成测试和 CI 配置。当前开发机未提供隔离 Redis 服务，因此**本轮没有实跑真实 Redis 用例**；普通单元测试通过不能替代这一验证。默认执行后端测试时，这些外部依赖用例会明确跳过，不会连接业务 Redis。

## 测试覆盖

- 两个独立连接客户端、两个限流实例共用同一窗口。
- 64 个并发请求共享 11 次总额度，不因实例数量扩大额度。
- Redis 的真实 1 秒 TTL 到期后重新开放窗口。
- 关闭测试自身客户端后，`closed` 策略拒绝请求，不回退为本机新窗口；另一客户端仍遵守服务器上的额度。

测试类：`backend/src/test/java/im/tlgxy/cloud/core/cache/ratelimit/RedisRateLimiterIntegrationTest.java`。现有元数据缓存集成测试也在同一 CI Redis 作业运行。

## 在独立测试环境执行

仅使用专用 Redis：`127.0.0.1:16379`、数据库 `15`。新增限流测试拒绝其它端口，尤其不能将它改为生产常见的 `6379`。测试不读取项目 Redis 密码、不使用业务连接配置。

若测试服务器已安装 Docker，可先创建一个只用于测试的临时服务：

```bash
# 必须确认16379没有其它服务使用。仅绑定环回地址，不对公网开放。
docker run --detach --name student-cloud-redis-test \
  --publish 127.0.0.1:16379:6379 \
  redis:7.4-alpine

# 在项目根目录执行；显式开启，否则这些用例按设计跳过。
mvn -B -f backend/pom.xml \
  -Dtest=RedisStorageMetadataIntegrationTest,RedisRateLimiterIntegrationTest \
  -Dstudent.cloud.redis.integration=true \
  -Dstudent.cloud.redis.port=16379 test

# 仅移除上面自己创建的临时容器，不操作生产Redis/数据库。
docker rm --force student-cloud-redis-test
```

每条用例使用随机命名空间。结束时只删除本用例明确登记的完整 key；不执行 `FLUSHDB`、`FLUSHALL`、通配符删除，也不会停止共享 Redis 服务。测试故障场景只关闭自己的连接客户端。

## 验收标准

显式开启后，两个测试类都应通过且没有跳过。只有取得专用服务上的实际测试结果，才能声称完成真实 Redis 的共享额度、并发原子性、TTL 和故障关闭验证；这些用例不等同于上线容量压测或多实例全系统验收。
