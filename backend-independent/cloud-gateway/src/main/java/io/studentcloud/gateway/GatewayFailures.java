package io.studentcloud.gateway;

import java.util.concurrent.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** Uniform errors contain no backend hostname, internal route, exception text or credentials. */
@Component
@Order(-2)
public class GatewayFailures implements WebExceptionHandler {
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        if (error instanceof ResponseStatusException rejected) {
            HttpStatus candidate = HttpStatus.resolve(rejected.getStatusCode().value());
            if (candidate != null) status = candidate;
        } else if (error instanceof TimeoutException) status = HttpStatus.GATEWAY_TIMEOUT;
        if (status == HttpStatus.NOT_FOUND) return GatewayReplies.failure(exchange, status, "40400", "接口不存在");
        if (status.is4xxClientError()) return GatewayReplies.failure(exchange, status, "40000", "请检查请求地址、方法或字段");
        return GatewayReplies.failure(exchange, status, "50300", "服务暂时不可用，请稍后重试");
    }
}
