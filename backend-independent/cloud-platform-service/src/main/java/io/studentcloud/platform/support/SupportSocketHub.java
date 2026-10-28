package io.studentcloud.platform.support;

import tools.jackson.databind.ObjectMapper;
import io.studentcloud.account.AccountService;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.support.SupportConversation;
import io.studentcloud.support.SupportService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

/** Ephemeral, single-node notification transport. Durable content stays in authenticated HTTP APIs. */
@Component
public final class SupportSocketHub implements AutoCloseable {
    static final String BINDING = SupportSocketHub.class.getName() + ".binding";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TICKETS = 1024, MAX_CONNECTIONS = 128, MAX_PER_ACCOUNT = 4;
    private static final long TICKET_TTL = 30_000, IDLE_TIMEOUT = 90_000;
    private final AccountService accounts;
    private final SupportService support;
    private final ObjectMapper json;
    private final Clock clock;
    private final Set<String> origins;
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<Long, RateWindow> issuance = new HashMap<>();
    private final ConcurrentHashMap<String, Binding> sockets = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor delivery = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "support-notifications");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastSweep;

    public SupportSocketHub(AccountService accounts, SupportService support, ObjectMapper json, Clock clock,
            @Value("${student-cloud.web.allowed-origins}") String configuredOrigins) {
        this.accounts = accounts;
        this.support = support;
        this.json = json;
        this.clock = clock;
        this.origins = Set.copyOf(java.util.Arrays.stream(configuredOrigins.split(",")).map(String::trim).toList());
        delivery.setRemoveOnCancelPolicy(true);
    }

    @PostConstruct public void start() {
        delivery.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
    }

    public boolean allowedOrigin(String origin) { return origin != null && origins.contains(origin); }

    /** Only opaque session references are retained, never bearer tokens or publicly accessible hashes. */
    public synchronized Map<String,Object> issue(Actor supplied, String credential, String origin) {
        if (!allowedOrigin(origin)) throw rejected("客服连接来源无效", 403);
        AccountService.SessionReference authorization = accounts.bindSession(credential);
        Actor current = accounts.authenticateSession(authorization);
        if (current.id() != supplied.id()) throw rejected("请重新登录", 401);
        long now = clock.millis();
        tickets.values().removeIf(ticket -> ticket.expiresAt <= now);
        issuance.values().removeIf(window -> window.start + 60_000 <= now);
        RateWindow window = issuance.get(current.id());
        if (window == null) {
            if (issuance.size() >= MAX_TICKETS) throw busy();
            window = new RateWindow(now);
            issuance.put(current.id(), window);
        }
        if (window.count >= 8) throw rejected("连接请求过于频繁，请稍后重试", 429);
        window.count++;
        long ownPending = tickets.values().stream().filter(ticket -> ticket.actorId == current.id()).count();
        if (closed.get() || tickets.size() >= MAX_TICKETS || ownPending >= MAX_PER_ACCOUNT) throw busy();
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        long expiry = now + TICKET_TTL;
        tickets.put(hash(raw), new Ticket(current.id(), authorization, origin, expiry));
        return Map.of("path", "/ws/support", "ticket", raw, "expiresAt", expiry);
    }

    /** Removal is atomic even when multiple upgrade requests race with the same ticket. */
    synchronized Binding redeem(String raw, String origin) {
        if (closed.get() || !allowedOrigin(origin) || raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw rejected("客服连接凭据无效", 401);
        Ticket ticket = tickets.remove(hash(raw));
        if (ticket == null || ticket.expiresAt <= clock.millis() || !ticket.origin.equals(origin)) throw rejected("客服连接已过期，请重试", 401);
        Actor current = accounts.authenticateSession(ticket.authorization);
        if (current.id() != ticket.actorId) throw rejected("请重新登录", 401);
        return new Binding(current.id(), current.role(), ticket.authorization, clock.millis());
    }

    synchronized void connected(WebSocketSession session, Binding binding) {
        long own = sockets.values().stream().filter(value -> value.actorId == binding.actorId).count();
        if (closed.get() || sockets.size() >= MAX_CONNECTIONS || own >= MAX_PER_ACCOUNT) {
            closeSession(session, CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        try {
            accounts.authenticateSession(binding.authorization);
            if (!(session instanceof NativeWebSocketSession nativeSession)) throw new IllegalStateException("Native socket unavailable");
            Session nativeSocket = nativeSession.getNativeSession(Session.class);
            if (nativeSocket == null) throw new IllegalStateException("Native socket unavailable");
            nativeSocket.setMaxTextMessageBufferSize(256);
            nativeSocket.setMaxBinaryMessageBufferSize(1);
            nativeSocket.getAsyncRemote().setSendTimeout(3000);
            binding.session = session;
            binding.nativeSession = nativeSocket;
            sockets.put(session.getId(), binding);
        } catch (RuntimeException invalid) { closeSession(session, CloseStatus.POLICY_VIOLATION); }
    }

    /** Called only after the durable service transaction has successfully returned. No chat body is broadcast. */
    public void changed(SupportConversation conversation) {
        for (Binding binding : sockets.values()) {
            if (binding.initialRole == Role.ADMIN || binding.actorId == conversation.requesterId()) {
                synchronized (binding) {
                    String id = conversation.id();
                    if (!binding.pending.contains(id)) {
                        if (binding.pending.size() >= 32) { binding.overflow = true; continue; }
                        binding.pending.addLast(id);
                    }
                }
            }
        }
    }

    void heartbeat(WebSocketSession session, String content) {
        Binding binding = sockets.get(session.getId());
        if (binding == null) { closeSession(session, CloseStatus.POLICY_VIOLATION); return; }
        try {
            if (content.length() > 128 || !"ping".equals(json.readTree(content).path("type").asText())) throw rejected("消息格式无效", 400);
            synchronized (binding) {
                long now = clock.millis();
                if (binding.frameWindow + 60_000 <= now) { binding.frameWindow = now; binding.frames = 0; }
                if (++binding.frames > 12) throw rejected("心跳过于频繁", 429);
                binding.lastActivity = now;
                binding.pongPending = true;
            }
        } catch (Exception invalid) { disconnect(binding, CloseStatus.POLICY_VIOLATION); }
    }

    void disconnected(WebSocketSession session) { sockets.remove(session.getId()); }

    private void tick() {
        if (closed.get()) return;
        try {
            long now = clock.millis();
            boolean sweep = lastSweep + 15_000 <= now;
            if (sweep) {
                lastSweep = now;
                synchronized (this) {
                    tickets.values().removeIf(ticket -> ticket.expiresAt <= now);
                    issuance.values().removeIf(window -> window.start + 60_000 <= now);
                }
            }
            for (Binding binding : sockets.values()) {
                if (!binding.session.isOpen()) { disconnected(binding.session); continue; }
                if (binding.overflow) { disconnect(binding, CloseStatus.SERVICE_OVERLOAD); continue; }
                if (now - binding.lastActivity > IDLE_TIMEOUT) { disconnect(binding, CloseStatus.GOING_AWAY); continue; }
                String id;
                boolean pong;
                boolean ready;
                boolean sending;
                synchronized (binding) {
                    sending = binding.sending;
                    if (sending && now - binding.sendStartedAt > 5000) {
                        disconnect(binding, CloseStatus.SERVER_ERROR); continue;
                    }
                    id = binding.pending.peekFirst();
                    pong = binding.pongPending;
                    ready = binding.readyPending;
                    if (!sweep && (sending || id == null && !pong && !ready)) continue;
                }
                try {
                    // Session revocation and password/role/workspace changes take effect before every delivery.
                    Actor actor = accounts.authenticateSession(binding.authorization);
                    if (actor.id() != binding.actorId) throw rejected("请重新登录", 401);
                    if (sending || id == null && !pong && !ready) continue;
                    if (!ready && !pong && id != null) support.get(actor, id); // Fresh ownership and current account authorization.
                    // HTTP 101 can reach the browser before afterConnectionEstablished registers this binding.
                    // Readiness is delivered only from the registered socket set, before its queued changes.
                    String payload = ready ? "{\"type\":\"support.ready\"}"
                            : pong ? "{\"type\":\"pong\"}"
                            : json.writeValueAsString(Map.of("type", "support.changed", "conversationId", id));
                    synchronized (binding) {
                        if (ready) binding.readyPending = false;
                        else if (pong) binding.pongPending = false;
                        else binding.pending.removeFirst();
                        binding.sending = true;
                        binding.sendStartedAt = now;
                    }
                    binding.nativeSession.getAsyncRemote().sendText(payload, result -> {
                        synchronized (binding) { binding.sending = false; }
                        if (!result.isOK()) disconnect(binding, CloseStatus.SERVER_ERROR);
                    });
                } catch (Exception revokedOrUnavailable) {
                    disconnect(binding, revokedOrUnavailable instanceof ApplicationFault fault && fault.httpStatus() < 500
                            ? CloseStatus.POLICY_VIOLATION : CloseStatus.SERVER_ERROR);
                }
            }
        } catch (RuntimeException unavailable) {
            // A scheduler exception must not silently stop subsequent revocation checks.
            for (Binding binding : sockets.values()) disconnect(binding, CloseStatus.SERVER_ERROR);
        }
    }

    private void disconnect(Binding binding, CloseStatus status) {
        WebSocketSession session = binding.session;
        if (session != null) { sockets.remove(session.getId(), binding); closeSession(session, status); }
    }
    private static void closeSession(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (Exception ignored) { /* The peer may have already disappeared. */ }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Ticket hashing unavailable", unavailable); }
    }
    private static ApplicationFault rejected(String message, int status) { return new ApplicationFault("42000", status, message); }
    private static ApplicationFault busy() { return rejected("客服实时连接已满，请稍后重试", 429); }

    @Override @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        delivery.shutdownNow();
        for (Binding binding : sockets.values()) disconnect(binding, CloseStatus.GOING_AWAY);
        synchronized (this) { tickets.clear(); issuance.clear(); }
    }

    private record Ticket(long actorId, AccountService.SessionReference authorization, String origin, long expiresAt) { }
    private static final class RateWindow { final long start; int count; RateWindow(long start) { this.start = start; } }
    static final class Binding {
        final long actorId;
        final Role initialRole;
        final AccountService.SessionReference authorization;
        final ArrayDeque<String> pending = new ArrayDeque<>();
        volatile WebSocketSession session;
        volatile Session nativeSession;
        volatile long lastActivity;
        volatile boolean overflow;
        long frameWindow;
        long sendStartedAt;
        int frames;
        boolean sending, pongPending;
        boolean readyPending = true;
        Binding(long actorId, Role role, AccountService.SessionReference authorization, long now) {
            this.actorId = actorId; this.initialRole = role; this.authorization = authorization;
            this.lastActivity = now; this.frameWindow = now;
        }
    }
}
