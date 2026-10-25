package im.tlgxy.cloud.module.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Metadata notifications only. Chat text is persisted and fetched through authenticated REST. */
@Component
public class SupportSocketHandler extends TextWebSocketHandler {
    private final SupportSocketAuth auth;
    private final ObjectMapper json;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private static final class Connection {
        final WebSocketSession session;
        final SupportSocketAuth.Identity identity;
        final boolean administrator;
        volatile long lastSeen = System.currentTimeMillis();
        long window = lastSeen;
        int received;
        Connection(WebSocketSession session, SupportSocketAuth.Identity identity, boolean administrator) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024);
            this.identity = identity; this.administrator = administrator;
        }
    }

    public SupportSocketHandler(SupportSocketAuth auth, ObjectMapper json) { this.auth = auth; this.json = json; }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            var identity = (SupportSocketAuth.Identity) session.getAttributes().get(SupportSocketAuth.IDENTITY_ATTRIBUTE);
            var actor = auth.requireValid(identity);
            session.setTextMessageSizeLimit(1024);
            session.setBinaryMessageSizeLimit(1024);
            Connection connection = new Connection(session, identity, actor.getRole() == UserRoleEnum.ADMIN);
            synchronized (connections) {
                if (connections.size() >= 256 || connections.values().stream()
                        .filter(value -> value.identity.userId() == identity.userId()).count() >= 4) {
                    session.close(CloseStatus.POLICY_VIOLATION); return;
                }
                connections.put(session.getId(), connection);
            }
            send(connection, Map.of("type", "ready"));
        } catch (RuntimeException invalid) { session.close(CloseStatus.POLICY_VIOLATION); }
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Connection connection = connections.get(session.getId());
        if (connection == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        try {
            auth.requireValid(connection.identity);
            long now = System.currentTimeMillis();
            synchronized (connection) {
                if (now - connection.window >= 60_000) { connection.window = now; connection.received = 0; }
                if (++connection.received > 30 || message.getPayloadLength() > 256) {
                    terminate(connection); return;
                }
                connection.lastSeen = now;
            }
            var body = json.readTree(message.getPayload());
            if (body == null || !body.isObject() || !"ping".equals(body.path("type").asText())) {
                send(connection, Map.of("type", "error", "message", "请通过客服消息接口发送内容")); return;
            }
            send(connection, Map.of("type", "pong"));
        } catch (Exception invalid) { terminate(connection); }
    }

    /** Domain publisher emits only AFTER commit; no chat text is broadcast to an unrelated socket. */
    @EventListener public void changed(SupportChangedEvent event) {
        for (Connection connection : connections.values()) {
            if (connection.identity.userId() != event.ownerUserId() && !connection.administrator) continue;
            try {
                var actor = auth.requireValid(connection.identity);
                if (actor.getId() != event.ownerUserId() && actor.getRole() != UserRoleEnum.ADMIN) {
                    terminate(connection); continue;
                }
                send(connection, Map.of("type", "support.changed", "conversationId", event.conversationId()));
            } catch (Exception invalid) { terminate(connection); }
        }
    }

    @Scheduled(fixedDelay = 15_000) public void reap() {
        long now = System.currentTimeMillis();
        for (Connection connection : connections.values()) {
            try {
                if (!connection.session.isOpen() || now - connection.lastSeen > 75_000) { terminate(connection); continue; }
                auth.requireValid(connection.identity);
            } catch (RuntimeException invalid) { terminate(connection); }
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { connections.remove(session.getId()); }
    @Override public void handleTransportError(WebSocketSession session, Throwable failure) {
        Connection connection = connections.get(session.getId());
        if (connection != null) terminate(connection);
    }
    private void send(Connection connection, Object body) throws IOException { connection.session.sendMessage(new TextMessage(json.writeValueAsString(body))); }
    private void terminate(Connection connection) {
        connections.remove(connection.session.getId(), connection);
        try { connection.session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
    }
}
