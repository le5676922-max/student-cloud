package im.tlgxy.cloud.module.support;

import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.utils.PasswordCredentialFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One-use, short-lived websocket credentials. Long-lived login tokens never enter browser URLs. */
@Service
public class SupportSocketAuth {
    static final String IDENTITY_ATTRIBUTE = SupportSocketAuth.class.getName() + ".identity";
    private static final int MAX_TICKETS = 1024;
    private static final int MAX_USER_TICKETS = 4;
    private final UserService users;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingTicket> tickets = new HashMap<>();

    @Autowired
    public SupportSocketAuth(UserService users) { this(users, Clock.systemUTC()); }
    SupportSocketAuth(UserService users, Clock clock) { this.users = users; this.clock = clock; }

    // Server-side attributes only; never serialize Identity into a public response or event.
    public record Identity(int userId, String token, String fingerprint, String role) {
        @Override public String toString() { return "SupportIdentity[userId=" + userId + ", role=" + role + "]"; }
    }
    public record Ticket(String ticket, int expiresInSeconds, String path) {
        @Override public String toString() { return "SupportTicket[redacted, expiresInSeconds=" + expiresInSeconds + "]"; }
    }
    private record PendingTicket(Identity identity, long expiresAt) { }

    public synchronized Ticket issue(int userId, String token) {
        User actor = users.requireLoginUser(userId);
        Identity identity = new Identity(userId, token, PasswordCredentialFingerprint.of(actor), actor.getRole().name());
        requireValid(identity);
        long now = clock.millis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        if (tickets.size() >= MAX_TICKETS || tickets.values().stream()
                .filter(value -> value.identity.userId == userId).count() >= MAX_USER_TICKETS)
            throw new BizException("客服连接请求过多，请稍后重试");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new PendingTicket(identity, now + 30_000));
        return new Ticket(ticket, 30, "/ws/support");
    }

    public Identity consume(String ticket) {
        PendingTicket pending;
        synchronized (this) {
            pending = ticket != null && ticket.matches("[A-Za-z0-9_-]{43}") ? tickets.remove(ticket) : null;
        }
        if (pending == null || pending.expiresAt <= clock.millis()) throw invalid();
        requireValid(pending.identity);
        return pending.identity;
    }

    /** Every push/heartbeat revalidates token, account, workspace, role and password revocation. */
    public User requireValid(Identity identity) {
        try {
            if (identity == null || identity.token == null || identity.token.isBlank()) throw invalid();
            Object loginId = StpUtil.getStpLogic().getLoginIdByToken(identity.token);
            if (loginId == null || !Objects.equals(String.valueOf(identity.userId), String.valueOf(loginId))) throw invalid();
            if (StpUtil.getStpLogic().isOpenCheckActiveTimeout()) StpUtil.getStpLogic().checkActiveTimeout(identity.token);
            User actor = users.requireLoginUser(identity.userId);
            if (!Objects.equals(actor.getRole().name(), identity.role)
                    || !PasswordCredentialFingerprint.matches(identity.fingerprint, actor)) throw invalid();
            var session = StpUtil.getStpLogic().getTokenSessionByToken(identity.token, false);
            Object marker = session == null ? null : session.get(PasswordCredentialFingerprint.SESSION_KEY);
            if (marker != null && (!(marker instanceof String fingerprint)
                    || !PasswordCredentialFingerprint.matches(fingerprint, actor))) throw invalid();
            return actor;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static BizException invalid() { return new BizException("客服连接凭据已失效，请重新登录或重连"); }
}
