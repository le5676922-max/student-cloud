package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-use, purpose-bound email proofs. Delivery never holds a database transaction; consumption
 * participates in the account mutation's transaction, so a failed mutation does not burn its code.
 */
public final class EmailCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME = 10 * 60_000L, COOLDOWN = 60_000L, DAY = 24 * 60 * 60_000L;
    private static final int MAX_ATTEMPTS = 5, MAX_PER_DAY = 10, MAX_CHALLENGES = 10_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independentTransactions;
    private final Clock clock;
    private final EmailCodeSender sender;
    private final byte[] serverKey;

    public EmailCodeService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                            EmailCodeSender sender, String serverSecret) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.sender = Objects.requireNonNull(sender);
        if (serverSecret == null || serverSecret.length() < 32 || serverSecret.length() > 200
                || serverSecret.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Email proofs require a random server secret of 32 to 200 characters");
        this.serverKey = serverSecret.getBytes(StandardCharsets.UTF_8);
        independentTransactions = new TransactionTemplate(Objects.requireNonNull(manager));
        independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independentTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independentTransactions.setTimeout(5);
    }

    public boolean available() { return sender.available(); }

    /** Reserve first, deliver outside SQL, then activate exactly that generation. */
    public void request(String email, EmailPurpose purpose) {
        request(email,purpose,true,false);
    }

    /** The reset endpoint budgets all valid mailboxes alike and never reveals a delivery outcome. */
    public void requestPasswordReset(String email,boolean deliver) {
        request(email,EmailPurpose.RESET_PASSWORD,deliver,true);
    }

    private void request(String email,EmailPurpose purpose,boolean deliver,boolean hideDeliveryOutcome) {
        requireAvailable();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Email delivery must be requested outside an account transaction");
        String normalized = normalizeEmail(email);
        requirePurpose(purpose);
        String generation = UUID.randomUUID().toString().replace("-", "");
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String digest = digest(normalized, purpose, generation, code);
        independentTransactions.executeWithoutResult(status -> {
            // This tiny global lock bounds challenge row growth across application instances.
            jdbc.queryForObject("SELECT id FROM sc_email_budget WHERE id=1 FOR UPDATE", Integer.class);
            Challenge previous = load(normalized, purpose, true);
            long now = clock.millis();
            if (previous == null) {
                ensureCapacity(now);
                jdbc.update("INSERT INTO sc_email_challenge(email,purpose,status,expires_at,attempts,next_send_at,window_started_at,sent_count) VALUES(?,?,'EMPTY',0,0,0,?,0)", normalized, purpose.name(), now);
                previous = load(normalized, purpose, true);
            }
            if (previous == null) throw new IllegalStateException("Email challenge reservation is missing");
            if (previous.nextSendAt() > now) throw throttled();
            long window = previous.windowStartedAt();
            int count = previous.sentCount();
            if (window > now || now - window >= DAY) { window = now; count = 0; }
            if (count >= MAX_PER_DAY) throw throttled();
            jdbc.update("UPDATE sc_email_challenge SET code_hash=?,generation=?,status='SENDING',expires_at=?,attempts=0,next_send_at=?,window_started_at=?,sent_count=? WHERE email=? AND purpose=?",
                    deliver?digest:null, generation, now + LIFETIME, now + COOLDOWN, window, count + 1, normalized, purpose.name());
        });
        if(!deliver){
            independentTransactions.executeWithoutResult(status -> jdbc.update("UPDATE sc_email_challenge SET status='FAILED',code_hash=NULL WHERE email=? AND purpose=? AND generation=? AND status='SENDING'",
                    normalized,purpose.name(),generation));
            return;
        }
        try {
            sender.send(normalized, purpose, code);
        } catch (RuntimeException deliveryFailed) {
            try {
                independentTransactions.executeWithoutResult(status -> jdbc.update("UPDATE sc_email_challenge SET status='FAILED',code_hash=NULL WHERE email=? AND purpose=? AND generation=? AND status='SENDING'",
                        normalized, purpose.name(), generation));
            } catch (RuntimeException unavailableDatabase) {
                // A reserved SENDING generation is unusable even if failure marking cannot complete.
            }
            if(hideDeliveryOutcome)return;
            throw new ApplicationFault("MAIL_UNAVAILABLE", 503, "邮件发送失败，请60秒后重试");
        }
        Boolean activated = independentTransactions.execute(status -> jdbc.update("UPDATE sc_email_challenge SET status='SENT' WHERE email=? AND purpose=? AND generation=? AND status='SENDING' AND expires_at>?",
                normalized, purpose.name(), generation, clock.millis()) == 1);
        if (!Boolean.TRUE.equals(activated) && !hideDeliveryOutcome)
            throw new ApplicationFault("EMAIL_CODE_CHANGED", 409, "验证码已更新或过期，请重新获取并使用最新邮件");
    }

    /** A failed attempt commits before a public error is thrown; an outer rollback cannot erase it. */
    public Proof verify(String email, EmailPurpose purpose, String code) {
        requireAvailable();
        String normalized = normalizeEmail(email);
        requirePurpose(purpose);
        Proof proof = independentTransactions.execute(status -> {
            Challenge current = load(normalized, purpose, true);
            if (!usable(current, clock.millis())) return null;
            boolean matches = code != null && code.matches("[0-9]{6}")
                    && constantTime(current.hash(), digest(normalized, purpose, current.generation(), code));
            if (!matches) {
                jdbc.update("UPDATE sc_email_challenge SET attempts=attempts+1 WHERE email=? AND purpose=? AND generation=?",
                        normalized, purpose.name(), current.generation());
                return null;
            }
            return new Proof(this, normalized, purpose, current.generation(), current.hash());
        });
        if (proof == null) throw invalidCode();
        return proof;
    }

    /** Must run on this datasource inside the account creation/reset transaction. */
    public void consume(Proof proof) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || jdbc.getDataSource() == null || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Email proof consumption requires the account database transaction");
        if (proof == null || proof.issuer != this) throw invalidCode();
        Challenge current = load(proof.email, proof.purpose, true);
        if (!usable(current, clock.millis()) || !Objects.equals(current.generation(), proof.generation)
                || !constantTime(current.hash(), proof.hash)) throw invalidCode();
        if (jdbc.update("UPDATE sc_email_challenge SET status='USED',code_hash=NULL WHERE email=? AND purpose=? AND generation=? AND status='SENT'",
                proof.email, proof.purpose.name(), proof.generation) != 1) throw invalidCode();
    }

    /** No hash, code or generation accessor: this object is a local capability, never a browser DTO. */
    public static final class Proof {
        private final EmailCodeService issuer;
        private final String email;
        private final EmailPurpose purpose;
        private final String generation;
        private final String hash;
        private Proof(EmailCodeService issuer, String email, EmailPurpose purpose, String generation, String hash) {
            this.issuer = issuer; this.email = email; this.purpose = purpose; this.generation = generation; this.hash = hash;
        }
        public String email() { return email; }
        public EmailPurpose purpose() { return purpose; }
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.length() > 274 || email.chars().anyMatch(Character::isISOControl)) throw invalidEmail();
        String value = email.trim().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at < 1 || at != value.lastIndexOf('@') || at > 64) throw invalidEmail();
        String local = value.substring(0, at), domain;
        try { domain = IDN.toASCII(value.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException invalid) { throw invalidEmail(); }
        if (!local.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+") || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || domain.length() > 253 || domain.indexOf('.') < 1 || domain.endsWith(".") || domain.contains("..")) throw invalidEmail();
        String normalized = local + '@' + domain;
        if (normalized.length() > 254) throw invalidEmail();
        return normalized;
    }

    private void ensureCapacity(long now) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sc_email_challenge", Integer.class);
        if (count != null && count < MAX_CHALLENGES) return;
        List<CleanupKey> old = jdbc.query("SELECT email,purpose,expires_at FROM sc_email_challenge WHERE expires_at<? AND next_send_at<? ORDER BY expires_at LIMIT 200",
                (row, index) -> new CleanupKey(row.getString("email"), row.getString("purpose"), row.getLong("expires_at")), now - DAY, now);
        for (CleanupKey key : old)
            jdbc.update("DELETE FROM sc_email_challenge WHERE email=? AND purpose=? AND expires_at=? AND next_send_at<?", key.email(), key.purpose(), key.expiresAt(), now);
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM sc_email_challenge", Integer.class);
        if (remaining == null || remaining >= MAX_CHALLENGES)
            throw new ApplicationFault("MAIL_BUSY", 429, "邮件服务繁忙，请60秒后重试");
    }

    private Challenge load(String email, EmailPurpose purpose, boolean lock) {
        List<Challenge> rows = jdbc.query("SELECT code_hash,generation,status,expires_at,attempts,next_send_at,window_started_at,sent_count FROM sc_email_challenge WHERE email=? AND purpose=?" + (lock ? " FOR UPDATE" : ""),
                EmailCodeService::challenge, email, purpose.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static Challenge challenge(ResultSet row, int index) throws SQLException {
        return new Challenge(row.getString("code_hash"), row.getString("generation"), row.getString("status"), row.getLong("expires_at"),
                row.getInt("attempts"), row.getLong("next_send_at"), row.getLong("window_started_at"), row.getInt("sent_count"));
    }
    private static boolean usable(Challenge value, long now) {
        return value != null && "SENT".equals(value.state()) && value.expiresAt() > now && value.attempts() < MAX_ATTEMPTS
                && value.hash() != null && value.hash().length() == 64 && value.generation() != null;
    }
    private String digest(String email, EmailPurpose purpose, String generation, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serverKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + '\n' + purpose.name() + '\n' + generation + '\n' + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Email challenge hashing unavailable"); }
    }
    private static boolean constantTime(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private void requireAvailable() { if (!available()) throw new ApplicationFault("MAIL_NOT_CONFIGURED", 503, "邮件服务尚未配置，请联系管理员"); }
    private static void requirePurpose(EmailPurpose purpose) { if (purpose == null) throw new ApplicationFault("41000", 400, "验证码用途无效"); }
    private static ApplicationFault invalidEmail() { return new ApplicationFault("41000", 400, "请填写有效邮箱地址"); }
    private static ApplicationFault invalidCode() { return new ApplicationFault("EMAIL_CODE_INVALID", 400, "验证码错误、过期或已使用，请重新获取"); }
    private static ApplicationFault throttled() { return new ApplicationFault("41005", 429, "验证码发送过于频繁，请稍后重试"); }
    private record Challenge(String hash, String generation, String state, long expiresAt, int attempts,
                             long nextSendAt, long windowStartedAt, int sentCount) { }
    private record CleanupKey(String email, String purpose, long expiresAt) { }
}
