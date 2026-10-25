package im.tlgxy.cloud.module.support;

import im.tlgxy.cloud.core.config.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Configuration
@EnableWebSocket
public class SupportWebSocketConfiguration implements WebSocketConfigurer {
    private final SupportSocketHandler handler;
    private final SupportSocketAuth auth;
    private final CorsProperties cors;
    public SupportWebSocketConfiguration(SupportSocketHandler handler, SupportSocketAuth auth, CorsProperties cors) {
        this.handler = handler; this.auth = auth; this.cors = cors;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/support").addInterceptors(new TicketHandshake(auth))
                // Spring additionally accepts same-origin; cross-origin deployments use the existing explicit CORS list.
                .setAllowedOrigins(cors.getAllowedOrigins().toArray(String[]::new));
    }

    static final class TicketHandshake implements HandshakeInterceptor {
        private final SupportSocketAuth auth;
        TicketHandshake(SupportSocketAuth auth) { this.auth = auth; }
        @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                 WebSocketHandler handler, Map<String, Object> attributes) {
            try {
                var parameters = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                if (parameters.size() != 1 || parameters.get("ticket") == null || parameters.get("ticket").size() != 1)
                    throw new IllegalArgumentException("Invalid socket parameters");
                attributes.put(SupportSocketAuth.IDENTITY_ATTRIBUTE, auth.consume(parameters.getFirst("ticket")));
                return true;
            } catch (RuntimeException invalid) { response.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
        }
        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                              WebSocketHandler handler, Exception exception) { }
    }
}
