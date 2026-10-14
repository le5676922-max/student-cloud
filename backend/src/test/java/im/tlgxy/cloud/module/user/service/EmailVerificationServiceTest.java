package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.module.user.config.RegistrationMailProperties;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {
    @TempDir Path work;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    MutableClock clock;
    CaptureMail sender;
    RegistrationMailProperties properties;
    EmailVerificationService service;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + work.resolve("mail.db"));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V66__email_verification.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        clock = new MutableClock();
        sender = new CaptureMail();
        properties = new RegistrationMailProperties();
        properties.setEnabled(true);
        properties.setHost("127.0.0.1");
        properties.setAuth(false);
        properties.setSsl(false);
        properties.setFrom("mailer@example.test");
        properties.setCodeSecret("unit-test-secret-never-used-in-production-2026");
        service = new EmailVerificationService(jdbc, manager, sender, properties, clock);
    }

    @Test
    void unavailableSenderNeverIssuesFakeCode() {
        properties.setEnabled(false);
        assertFalse(service.isAvailable());
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        assertEquals(0, count());
        properties.setEnabled(true);
        properties.setHost("smtp.qq.com");
        assertFalse(service.isAvailable(), "Remote SMTP requires TLS");
        properties.setSsl(true);
        properties.setAuth(true);
        assertFalse(service.isAvailable(), "Authenticated SMTP requires credentials");
    }

    @Test
    void cooldownNormalizationAndSingleUsePersist() throws Exception {
        service.sendCode("  Alice@Example.test  ", "REGISTER");
        String code = sender.code();
        assertEquals("alice@example.test", jdbc.queryForObject("SELECT email FROM email_verification", String.class));
        String digest = jdbc.queryForObject("SELECT code_digest FROM email_verification", String.class);
        assertNotNull(digest);
        assertEquals(64, digest.length());
        assertNotEquals(code, digest);
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        service.consumeCode("ALICE@example.test", "REGISTER", code);
        assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification", Integer.class));
        assertEquals("", jdbc.queryForObject("SELECT code_digest FROM email_verification", String.class));
        assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", code));
    }

    @Test
    void wrongAttemptsCommitDespiteThrownBusinessErrors() throws Exception {
        service.sendCode("alice@example.test", "REGISTER");
        String code = sender.code();
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", wrong));
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM email_verification", Integer.class));
        assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", code));
        clock.advance(60_001);
        service.sendCode("alice@example.test", "REGISTER");
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM email_verification", Integer.class));
        service.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void expiryAndWrongEmailCannotConsume() throws Exception {
        service.sendCode("alice@example.test", "REGISTER");
        String code = sender.code();
        assertThrows(BizException.class, () -> service.consumeCode("bob@example.test", "REGISTER", code));
        clock.advance(600_000);
        assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", code));
    }

    @Test
    void smtpFailureLeavesOnlyCooldownAndNoEffectiveCode() throws Exception {
        sender.fail = true;
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        assertEquals(1, count());
        assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification", Integer.class));
        assertEquals("", jdbc.queryForObject("SELECT code_digest FROM email_verification", String.class));
        assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", "123456"));
        sender.fail = false;
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        clock.advance(60_001);
        service.sendCode("alice@example.test", "REGISTER");
        service.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void smtpIsOutsideTransactionAndPendingDeliveryCannotBeConsumedOrResent() throws Exception {
        sender.onSend = () -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification", Integer.class));
            assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", "123456"));
            var other = new EmailVerificationService(jdbc, manager, sender, properties, clock);
            assertThrows(BizException.class, () -> other.sendCode("alice@example.test", "REGISTER"));
        };
        service.sendCode("alice@example.test", "REGISTER");
        service.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void registrationAndPasswordRecoveryChallengesCannotBeMixed() throws Exception {
        service.sendCode("alice@example.test", "REGISTER");
        String registration = sender.code();
        service.sendCode("alice@example.test", EmailVerificationService.PASSWORD_RESET);
        String reset = sender.code();
        assertEquals(2, count());
        assertEquals("学生云盘密码重置验证码", sender.message.getSubject());
        if (!registration.equals(reset))
            assertThrows(BizException.class, () -> service.consumeCode("alice@example.test", "REGISTER", reset));
        service.consumeCode("alice@example.test", EmailVerificationService.PASSWORD_RESET, reset);
        service.consumeCode("alice@example.test", "REGISTER", registration);
    }

    @Test
    void expiredSendReservationCannotActivateOrEraseNewerDelivery() throws Exception {
        sender.onSend = () -> {
            sender.onSend = null;
            clock.advance(60_001);
            service.sendCode("alice@example.test", "REGISTER");
        };
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        service.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void sharedGlobalLimitRejectsBeforeReservationOrSmtp() {
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        var limited = new EmailVerificationService(jdbc, manager, sender, properties, limiter);
        assertThrows(BizException.class, () -> limited.sendCode("alice@example.test", "REGISTER"));
        verify(limiter).allow("mail-delivery", "global", 120, 60_000L);
        assertEquals(0, count());
        assertNull(sender.message);
    }

    @Test
    void localSmtpConcurrencyIsBoundedWithoutWaitingForAnotherSend() throws Exception {
        properties.setMaxConcurrentSends(1);
        ApiRateLimiter limiter = mock(ApiRateLimiter.class);
        when(limiter.allow(anyString(), anyString(), anyLong(), anyLong())).thenReturn(true);
        var limited = new EmailVerificationService(jdbc, manager, sender, properties, limiter);
        sender.onSend = () -> assertThrows(BizException.class, () -> limited.sendCode("bob@example.test", "REGISTER"));
        limited.sendCode("alice@example.test", "REGISTER");
        assertEquals(1, count());
        limited.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void failedOldSenderCannotInvalidateNewerSuccessfulDelivery() throws Exception {
        sender.onSend = () -> {
            sender.onSend = null;
            clock.advance(60_001);
            service.sendCode("alice@example.test", "REGISTER");
            sender.fail = true;
        };
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "REGISTER"));
        assertEquals(0, jdbc.queryForObject("SELECT consumed FROM email_verification", Integer.class));
        service.consumeCode("alice@example.test", "REGISTER", sender.code());
    }

    @Test
    void sharedSecretSupportsAnotherInstanceAndRestartWithoutSecretInvalidatesCodes() throws Exception {
        service.sendCode("alice@example.test", "REGISTER");
        var another = new EmailVerificationService(jdbc, manager, sender, properties, clock);
        another.consumeCode("alice@example.test", "REGISTER", sender.code());
        service.sendCode("bob@example.test", "REGISTER");
        properties.setCodeSecret("");
        var restarted = new EmailVerificationService(jdbc, manager, sender, properties, clock);
        assertThrows(BizException.class, () -> restarted.consumeCode("bob@example.test", "REGISTER", sender.code()));
    }

    @Test
    void nestedRegistrationTransactionRejectedBeforeItCanRollBackFailedAttempts() {
        var transaction = new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            service.consumeCode("alice@example.test", "REGISTER", "123456");
            return null;
        }));
    }

    @Test
    void invalidEmailPurposeOrWeakSecretRejectedAndExpiredRecordsPruned() {
        assertThrows(BizException.class, () -> service.sendCode("bad\r\nTo:admin@example.test", "REGISTER"));
        assertThrows(BizException.class, () -> service.sendCode("alice@example.test", "RESET"));
        properties.setCodeSecret("short");
        assertThrows(IllegalArgumentException.class, () -> new EmailVerificationService(jdbc, manager, sender, properties, clock));
        properties.setCodeSecret("unit-test-secret-never-used-in-production-2026");
        service.sendCode("alice@example.test", "REGISTER");
        clock.advance(90_000_000);
        service.removeExpiredChallenges();
        assertEquals(0, count());
    }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM email_verification", Integer.class); }

    private static class CaptureMail extends JavaMailSenderImpl {
        MimeMessage message;
        boolean fail;
        Runnable onSend;
        @Override public void send(MimeMessage... messages) {
            message = messages[0];
            if (onSend != null) onSend.run();
            if (fail) throw new MailSendException("Test sender unavailable");
        }
        String code() throws Exception {
            var match = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(message.getContent().toString());
            assertTrue(match.find());
            return match.group(1);
        }
    }

    private static class MutableClock extends Clock {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        void advance(long milliseconds) { now.addAndGet(milliseconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }
}
