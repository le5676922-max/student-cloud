# Student Cloud Gateway

基于 Spring Cloud Gateway Server WebFlux 的独立入口进程。它不依赖数据库或任何业务实现模块，不在网关里操作用户、文件或知识库。

## 当前组件

- Spring Cloud Gateway：HTTP/WebSocket 路由，保留浏览器现有接口路径。
- Spring Cloud LoadBalancer + SimpleDiscoveryClient：通过明确配置的实例地址发现服务，使用 `lb://cloud-platform`、`lb://cloud-content`、`lb://cloud-intelligence`。
- Spring Cloud CircuitBreaker + Resilience4j：仅对几个短小、无副作用的 GET 初始化配置接口设置熔断与 3 秒限制。
- WebFlux/Reactor Netty：流式代理，不把上传、下载和 SSE 聚合为整个响应。

网关没有自动重试过滤器，LoadBalancer 重试也显式关闭，避免保存、发送消息或上传被重复执行。下载和明确的 SSE 路径不套元数据熔断器；业务服务仍应自行控制任务和 I/O 预算。

## 路由边界

| 范围 | 目标 |
| --- | --- |
| 初始化、登录、空间管理员/成员账号、人工服务 | platform |
| 云盘、课程、云盘申请、本地盘审批、成员存储授权 | content |
| 长期记忆、反馈、审核知识、任务、助手入口 | intelligence |
| `/ws/support` | 配置的单个 platform WebSocket 实例 |

成员的云盘授权路径优先于普通成员管理路径；知识管理优先于其它平台路径。未知路径返回 JSON 404，不自动把所有 `/api/**` 转发给某个服务，也不返回 HTML 假成功。

公网访问 `/internal/**` 被拒绝，浏览器传入的 `X-StudentCloud-*` 服务凭据/身份声明被移除。用户登录凭据继续由目标业务服务验证，不能把经过网关理解成已获得业务权限。`Origin` 原样保留，确保客服票据签发与 WebSocket 握手绑定同一浏览器来源。

网关从真实 socket 地址产生客户端限流上下文，以共享服务密钥签名后转发；它只用于区分请求预算，不代替用户认证。默认不信任 `X-Real-IP` 或 `X-Forwarded-For`。只有 socket 对端匹配明确配置的代理 IP，才接受该代理覆盖的单个数值 `X-Real-IP`；禁止按任意请求头决定身份或限流来源。

`/healthz` 仅表示网关进程存活，不代表所有后端就绪。详细管理端点仅监听本机管理端口，未暴露服务配置或路由修改入口。

## 配置

| 变量 | 默认值 |
| --- | --- |
| `SC_BIND_ADDRESS` | `127.0.0.1` |
| `SC_PORT` | `18093` |
| `SC_PLATFORM_URI` | `http://127.0.0.1:18101` |
| `SC_CONTENT_URI` | `http://127.0.0.1:18102` |
| `SC_INTELLIGENCE_URI` | `http://127.0.0.1:18103` |
| `SC_PLATFORM_WS_URI` | `ws://127.0.0.1:18101` |
| `SC_ALLOWED_ORIGINS` | 本机 5173 的明确来源列表 |
| `SC_INTERNAL_KEY` | 必须提供至少 32 字符的内部共享密钥 |
| `SC_GATEWAY_TRUSTED_PROXY_IPS` | 空；可显式列出最多 16 个可信代理 IP，不支持宽泛 CIDR |

客服票据/连接目前在单 platform 实例内，`SC_PLATFORM_WS_URI` 必须指向签发票据的同一实例。添加多个 platform 实例前，需要实现共享票据和跨实例通知，不能直接在这里轮询到任意副本。

Gateway 先升级浏览器，再连接上游 WebSocket。上游因失效/重放票据返回401/403时，已升级的浏览器连接以1008关闭；HTTP101不能作为认证成功依据。`support.ready` 才表示服务端已完成订阅并可补读持久化消息。

客户端上下文协议：`X-StudentCloud-Client-Ip`、`X-StudentCloud-Client-At`（epoch 毫秒）、`X-StudentCloud-Client-Signature`。签名为 HMAC-SHA256 的无填充 base64url，正文依次为 `at + "\n" + ip + "\n" + METHOD + "\n" + rawPath`，密钥为 `SC_INTERNAL_KEY` 的 UTF-8 字节。接收服务验证时效与签名后才能使用 IP；不将会话令牌或查询参数写入此上下文。

运行及两台 ECS 的配置见 [微服务部署说明](../deploy/microservices/README.md)。本轮已通过隔离 MySQL 与 Gateway 的基本流程，尚未构建运行正式 Docker 镜像或验收 ECS，更不把接入框架等同于全部业务重写完成。

参考：[Spring Cloud 版本对应](https://spring.io/projects/spring-cloud/)、[Gateway 5 CircuitBreaker 过滤器](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/circuitbreaker-filter-factory.html)、[SimpleDiscoveryClient](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html#simplediscoveryclient)。
