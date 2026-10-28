package io.studentcloud.platform.support;

import io.studentcloud.common.ApplicationFault;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Ticket authentication is intentionally separate from HTTP bearer authentication. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class SupportSocketConfiguration implements WebSocketConfigurer {
    private final SupportSocketHub hub;
    private final String[] origins;
    public SupportSocketConfiguration(SupportSocketHub hub,
            @Value("${student-cloud.web.allowed-origins}") String configured) {
        this.hub = hub;
        this.origins = Arrays.stream(configured.split(",")).map(String::trim).distinct().toArray(String[]::new);
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(hub), "/ws/support").setAllowedOrigins(origins)
                .addInterceptors(new TicketHandshake(hub));
    }

    private record TicketHandshake(SupportSocketHub hub) implements HandshakeInterceptor {
        @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler handler, Map<String,Object> attributes) {
            response.getHeaders().setCacheControl("no-store");
            String query = request.getURI().getRawQuery();
            String origin = request.getHeaders().getOrigin();
            if (!hub.allowedOrigin(origin)) { response.setStatusCode(HttpStatus.FORBIDDEN); return false; }
            if (query == null || !query.matches("ticket=[A-Za-z0-9_-]{43}")) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
            }
            try {
                attributes.put(SupportSocketHub.BINDING, hub.redeem(query.substring(7), origin));
                return true;
            } catch (ApplicationFault invalid) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }
        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler handler, Exception exception) { }
    }

    private static final class Handler extends TextWebSocketHandler {
        private final SupportSocketHub hub;
        Handler(SupportSocketHub hub) { this.hub = hub; }
        @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            session.setTextMessageSizeLimit(256);
            session.setBinaryMessageSizeLimit(1);
            Object binding = session.getAttributes().remove(SupportSocketHub.BINDING);
            if (!(binding instanceof SupportSocketHub.Binding authenticated)) {
                session.close(CloseStatus.POLICY_VIOLATION); return;
            }
            hub.connected(session, authenticated);
        }
        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            hub.heartbeat(session, message.getPayload());
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { hub.disconnected(session); }
        @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            hub.disconnected(session);
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
