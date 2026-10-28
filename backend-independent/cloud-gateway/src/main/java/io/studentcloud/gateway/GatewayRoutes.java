package io.studentcloud.gateway;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {
    @Bean RouteLocator publicRoutes(RouteLocatorBuilder builder,
            @Value("${student-cloud.gateway.platform-websocket-uri}") String websocket) {
        URI target = URI.create(websocket);
        if (!("ws".equals(target.getScheme()) || "wss".equals(target.getScheme()))
                || target.getHost() == null || target.getUserInfo() != null || target.getQuery() != null
                || target.getFragment() != null || !(target.getPath() == null || target.getPath().isEmpty())) {
            throw new IllegalArgumentException("Configure an explicit platform WebSocket origin");
        }
        return builder.routes()
                // Tickets belong to one platform instance. This route must target that same instance.
                .route("platform-websocket", r -> r.path("/ws/support").and().method(HttpMethod.GET)
                        .uri(websocket))
                // Only short, safe metadata GETs use the circuit breaker. No body caching or retry filter.
                .route("platform-bootstrap", r -> r.path("/api/site/config/global", "/api/install/status",
                                "/user/registration/config", "/user/password-reset/config").and().method(HttpMethod.GET)
                        .filters(f -> f.circuitBreaker(c -> c.setName("platformMetadata")
                                .setFallbackUri("forward:/__gateway/unavailable")
                                .addStatusCode("INTERNAL_SERVER_ERROR").addStatusCode("SERVICE_UNAVAILABLE")
                                .addStatusCode("GATEWAY_TIMEOUT")))
                        .uri("lb://cloud-platform"))
                // Streamed downloads bypass circuit-breaker response buffering and metadata deadlines.
                .route("content-downloads", r -> r.path("/api/drives/*/content", "/api/drives/*/preview", "/api/courses/*/materials/*/download")
                        .and().method(HttpMethod.GET).metadata("response-timeout", -1).uri("lb://cloud-content"))
                .route("content", r -> r.path("/api/drives", "/api/drives/**", "/api/storage/list",
                                "/api/storage/files", "/api/storage/file/item", "/api/site/config/storage", "/api/uploads/resumable/**",
                                "/api/shares", "/api/shares/**", "/admin/file-business/overview",
                                "/api/site/config/userRootPath/*", "/api/file/operator/**", "/api/uploads/resumable/capabilities",
                                "/api/courses", "/api/courses/**", "/api/learning/**", "/api/workspace-cloud", "/api/workspace-cloud/**",
                                "/api/local-storage-requests", "/api/local-storage-requests/**",
                                "/admin/local-storage-requests", "/admin/local-storage-requests/**",
                                "/admin/support-storage", "/admin/storage-params", "/admin/storages", "/admin/storage/**", "/admin/cache/**",
                                "/admin/storage-local-requests", "/admin/storage-local-requests/**",
                                "/api/workspace-storage/local-requests", "/api/workspace-storage/local-requests/**",
                                "/api/workspace-admin/members/*/permissions",
                                "/api/workspace-admin/members/*/drives/*/grant")
                        .uri("lb://cloud-content"))
                .route("intelligence-streams", r -> r.path("/api/ai/chat/stream", "/api/ai/chat/stream/**", "/api/ai/chat/files/stream/**", "/api/ai/agent/stream")
                        .metadata("response-timeout", -1).uri("lb://cloud-intelligence"))
                .route("intelligence", r -> r.path("/api/ai/**", "/api/tasks", "/api/tasks/**",
                                "/api/agent/**", "/admin/ai-feedback", "/admin/ai-feedback/**",
                                "/admin/ai-knowledge/**", "/api/platform/knowledge", "/api/platform/knowledge/**")
                        .uri("lb://cloud-intelligence"))
                .route("platform", r -> r.path("/user/**", "/api/install", "/api/install/**",
                                "/api/site/config/global", "/api/sso/list", "/api/support/**",
                                "/api/platform/workspace-administrators", "/api/platform/workspace-administrators/**",
                                "/api/workspace-admin/members", "/api/workspace-admin/members/**",
                                "/api/workspace-admin/summary", "/api/workspace-admin/invitations", "/api/workspace-admin/invitations/**",
                                "/api/workspace-admin/join-requests", "/api/workspace-admin/join-requests/**",
                                "/admin/overview", "/admin/overview/**", "/admin/tenants", "/admin/tenant",
                                "/admin/user/**", "/admin/config", "/admin/config/site", "/admin/config/view")
                        .uri("lb://cloud-platform"))
                .build();
    }
}
