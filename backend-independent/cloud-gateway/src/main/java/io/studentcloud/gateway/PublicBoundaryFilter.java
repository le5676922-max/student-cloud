package io.studentcloud.gateway;

import java.util.ArrayList;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Browser-provided service credentials or claimed actor headers are never trusted or forwarded. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicBoundaryFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/internal") || path.startsWith("/internal/")) {
            return GatewayReplies.failure(exchange, HttpStatus.FORBIDDEN, "40300", "内部接口不对外开放");
        }
        var request = exchange.getRequest().mutate().headers(headers -> {
            for (String name : new ArrayList<>(headers.headerNames())) {
                if (name.toLowerCase(Locale.ROOT).startsWith("x-studentcloud-")) headers.remove(name);
            }
        }).build();
        // Preserve Origin exactly: support tickets bind the HTTP request and subsequent upgrade to it.
        return chain.filter(exchange.mutate().request(request).build());
    }
}
