package io.studentcloud.gateway;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class GatewayReplies {
    private GatewayReplies() { }
    static Mono<Void> failure(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        // All parameters are constants chosen by this gateway, never supplier or request strings.
        String body = "{\"code\":\"" + code + "\",\"msg\":\"" + message
                + "\",\"data\":null,\"traceId\":\"" + UUID.randomUUID() + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
