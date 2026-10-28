# 服务调用客户端

只依赖账号纯 API、Spring RestClient 和 Spring Cloud 客户端组件，不依赖身份数据库实现。

`ServiceClientConfiguration` 显式创建带 LoadBalancer 的 `serviceRestClientBuilder`、有界执行池与熔断配置。`IdentityClientConfiguration` 导入该传输配置并创建身份客户端，通过逻辑服务名 `cloud-platform` 访问身份接口；平台仅导入传输配置访问内容统计，不注册远程自身认证 Bean。实例地址来自 SimpleDiscoveryClient，默认不开重试；各服务装配须设置独立服务密钥。

连接超时1秒，读取2秒；CircuitBreaker/Resilience4j限时3秒。执行池最多8线程、队列24，饱和返回不可用。4xx 业务拒绝在熔断结果外解释，不把一个无权成员作为全体用户的依赖故障。没有任何“不可用时默认有权限”的降级。

身份客户端同时实现 `AccountDirectory` 与 `SessionAuthenticator`，仅注册一个实现 Bean，避免按接口包装导致重复候选。返回值必须具有成功状态和正确响应体；不接受供应者网页或空2xx作为有效身份。

当前不缓存长期授权快照，以保证注销和停用在新的业务校验中生效。缓存应根据实际热点接入，并明确撤销语义，不能仅为减少查询跳过实时权限复核。
