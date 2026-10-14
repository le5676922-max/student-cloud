package im.tlgxy.cloud.module.user.service;

import cn.hutool.core.lang.Validator;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.module.user.config.RegistrationMailProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Single-use codes persisted as keyed digests, with database-wide cooldown and attempt limits.
 * consumeCode must precede the registration write transaction: failed guesses must not roll back.
 */
@Service
@Slf4j
public class EmailVerificationService {
    public static final String REGISTER = "REGISTER";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    static final long VALIDITY_MS = Duration.ofMinutes(10).toMillis();
    static final long COOLDOWN_MS = Duration.ofSeconds(60).toMillis();
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JavaMailSender sender;
    private final RegistrationMailProperties settings;
    private final Clock clock;
    private final byte[] secret;
    private final String insertIfAbsentSql;
    private final Semaphore sends;
    private ApiRateLimiter rateLimiter;

    @Autowired
    public EmailVerificationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                    @Qualifier("registrationMailSender") JavaMailSender sender,
                                    RegistrationMailProperties settings, ApiRateLimiter rateLimiter) {
        this(jdbc, manager, sender, settings, Clock.systemUTC());
        this.rateLimiter = rateLimiter;
    }

    // Clock injection is kept package-private for expiry tests; production always uses UTC.
    EmailVerificationService(JdbcTemplate jdbc, PlatformTransactionManager manager, JavaMailSender sender,
                             RegistrationMailProperties settings, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.sender = sender;
        this.settings = settings;
        this.clock = clock;
        this.sends = new Semaphore(Math.max(1, Math.min(settings.getMaxConcurrentSends(), 32)));
        boolean sqlite = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "SQLite".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
        this.insertIfAbsentSql = sqlite
                ? "INSERT INTO email_verification (id,email,purpose,code_digest,nonce,expires_at,resend_at,attempts,consumed,updated_at) VALUES (?,?,?,'','',0,0,0,0,?) ON CONFLICT(id) DO NOTHING"
                : "INSERT INTO email_verification (id,email,purpose,code_digest,nonce,expires_at,resend_at,attempts,consumed,updated_at) VALUES (?,?,?,'','',0,0,0,0,?) ON DUPLICATE KEY UPDATE id=id";
        if (settings.getCodeSecret() != null && !settings.getCodeSecret().isBlank()) {
            if (settings.getCodeSecret().length() < 32) throw new IllegalArgumentException("MAIL_CODE_SECRET must have at least 32 characters");
            this.secret = settings.getCodeSecret().getBytes(StandardCharsets.UTF_8);
        } else {
            this.secret = new byte[32];
            RANDOM.nextBytes(this.secret);
        }
    }

    public boolean isAvailable() {
        boolean loopback = "127.0.0.1".equals(settings.getHost()) || "localhost".equals(settings.getHost());
        return settings.isEnabled() && settings.getHost() != null && !settings.getHost().isBlank()
                && settings.getPort() > 0 && settings.getPort() <= 65535
                && Validator.isEmail(fromAddress())
                && (settings.isSsl() || settings.isStarttls() || loopback)
                && (!settings.isAuth() || (settings.getUsername() != null && !settings.getUsername().isBlank()
                && settings.getPassword() != null && !settings.getPassword().isBlank()));
    }

    public void sendCode(String rawEmail, String purpose) {
        String email = normalizedEmail(rawEmail);
        requirePurpose(purpose);
        requireOwnTransaction();
        if (!isAvailable()) throw new BizException("邮箱验证尚未配置，请联系管理员配置 QQ 邮箱 SMTP 服务");
        if (!sends.tryAcquire()) throw new BizException("邮件服务繁忙，请60秒后重试");
        try {
            if (rateLimiter != null && !rateLimiter.allow("mail-delivery", "global",
                    Math.max(1, Math.min(settings.getMaxSendsPerMinute(), 10_000)), 60_000L))
                throw new BizException("邮件服务繁忙，请60秒后重试");
            deliverCode(email, purpose);
        } finally {
            sends.release();
        }
    }

    private void deliverCode(String email, String purpose) {
        String id = challengeId(email, purpose);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String nonce = HexFormat.of().formatHex(salt);
        long now = clock.millis();
        transactions.executeWithoutResult(status -> {
            // There is one row per email/purpose; the conditional update below owns the lock.
            jdbc.update(insertIfAbsentSql, id, email, purpose, now);
            // Reserve a persisted cooldown, but do not make the code usable before delivery.
            // nonce identifies this send attempt; an older sender cannot activate a newer code.
            int changed = jdbc.update("UPDATE email_verification SET code_digest='',nonce=?,expires_at=0,resend_at=?,attempts=0,consumed=1,updated_at=? WHERE id=? AND resend_at<=?",
                    nonce, now + COOLDOWN_MS, now, id, now);
            if (changed != 1) throw new BizException("验证码发送过于频繁，请60秒后重试");
        });
        try {
            // Network I/O never holds a database transaction or pooled connection.
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress());
            helper.setTo(email);
            boolean reset = PASSWORD_RESET.equals(purpose);
            helper.setSubject(reset ? "学生云盘密码重置验证码" : "学生云盘注册验证码");
            helper.setText((reset ? "你的密码重置验证码是：" : "你的注册验证码是：") + code
                    + (reset ? "\n仅可重置已绑定此邮箱且可用的账号。" : "")
                    + "\n\n10分钟内有效，请勿向他人泄露。若非本人操作，请忽略此邮件。", false);
            sender.send(message);
        } catch (MailException | MessagingException error) {
            // Failed delivery leaves only a cooldown, never an effective verification code.
            transactions.executeWithoutResult(status -> jdbc.update(
                    "UPDATE email_verification SET nonce='',updated_at=? WHERE id=? AND nonce=? AND consumed=1 AND expires_at=0",
                    clock.millis(), id, nonce));
            log.warn("Verification email delivery failed ({})", error.getClass().getSimpleName());
            throw new BizException("邮件发送失败，请60秒后重试或联系管理员检查邮箱配置");
        }
        long deliveredAt = clock.millis();
        Integer activated = transactions.execute(status -> jdbc.update(
                "UPDATE email_verification SET code_digest=?,expires_at=?,consumed=0,updated_at=? WHERE id=? AND nonce=? AND consumed=1 AND expires_at=0",
                digest(email, purpose, code, nonce), deliveredAt + VALIDITY_MS, deliveredAt, id, nonce));
        if (!Integer.valueOf(1).equals(activated))
            throw new BizException("本次验证码已被后续请求替代，请使用最新邮件或稍后重新获取");
    }

    public void consumeCode(String rawEmail, String purpose, String code) {
        String email = normalizedEmail(rawEmail);
        requirePurpose(purpose);
        requireOwnTransaction();
        long now = clock.millis();
        String id = challengeId(email, purpose);
        boolean valid = Boolean.TRUE.equals(transactions.execute(status -> {
            int locked = jdbc.update("UPDATE email_verification SET attempts=attempts+1,updated_at=? WHERE id=? AND consumed=0 AND expires_at>? AND attempts<?",
                    now, id, now, MAX_ATTEMPTS);
            if (locked != 1) return false;
            Map<String, Object> row = jdbc.queryForMap("SELECT code_digest,nonce FROM email_verification WHERE id=?", id);
            boolean matches = code != null && code.matches("[0-9]{6}")
                    && MessageDigest.isEqual(row.get("code_digest").toString().getBytes(StandardCharsets.US_ASCII),
                    digest(email, purpose, code, row.get("nonce").toString()).getBytes(StandardCharsets.US_ASCII));
            if (matches) jdbc.update("UPDATE email_verification SET consumed=1,code_digest='',nonce='' WHERE id=?", id);
            return matches;
        }));
        // Throw only after committing the failed attempt, not from inside the transaction callback.
        if (!valid) throw new BizException("验证码错误、已过期或已使用，请重新获取");
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void removeExpiredChallenges() {
        jdbc.update("DELETE FROM email_verification WHERE updated_at<? AND resend_at<=?",
                clock.millis() - Duration.ofDays(1).toMillis(), clock.millis());
    }

    private String fromAddress() {
        return settings.getFrom() == null || settings.getFrom().isBlank() ? settings.getUsername() : settings.getFrom();
    }

    private static String normalizedEmail(String input) {
        String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !Validator.isEmail(email)) throw new BizException("请输入有效的邮箱地址");
        return email;
    }

    private static void requirePurpose(String purpose) {
        if (!REGISTER.equals(purpose) && !PASSWORD_RESET.equals(purpose)) throw new BizException("不支持的邮箱验证用途");
    }

    private static void requireOwnTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Email verification must run outside the account write transaction");
    }

    private static String challengeId(String email, String purpose) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((purpose + ":" + email).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    private String digest(String email, String purpose, String code, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + "\0" + purpose + "\0" + nonce + "\0" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
