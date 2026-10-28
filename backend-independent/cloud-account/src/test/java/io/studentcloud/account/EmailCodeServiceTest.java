package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class EmailCodeServiceTest {
    private static final String EMAIL = "synthetic@example.invalid";
    private static final String SERVER_SECRET = "synthetic-server-secret-for-isolated-email-tests";
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MutableClock clock;
    private FakeSender sender;
    private EmailCodeService codes;

    @BeforeEach void database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:student_cloud_email_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-email-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE synthetic_account(id INTEGER PRIMARY KEY)");
        manager = new DataSourceTransactionManager(source);
        clock = new MutableClock();
        sender = new FakeSender();
        codes = new EmailCodeService(jdbc, manager, clock, sender, SERVER_SECRET);
    }
    @AfterEach void shutdown() { jdbc.execute("SHUTDOWN"); }

    @Test void deliveryHasNoSqlTransactionAndOnlyDigestIsPersisted() {
        sender.observe = () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        codes.request(" Synthetic@Example.Invalid ", EmailPurpose.REGISTER);
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM sc_email_challenge", String.class));
        String hash = jdbc.queryForObject("SELECT code_hash FROM sc_email_challenge", String.class);
        assertTrue(hash != null && hash.matches("[a-f0-9]{64}") && !hash.equals(sender.code(EMAIL, EmailPurpose.REGISTER)));
        assertEquals(EMAIL, codes.verify(EMAIL, EmailPurpose.REGISTER, sender.code(EMAIL, EmailPurpose.REGISTER)).email());
    }

    @Test void consumptionRequiresSameDatasourceTransactionAndIsOneUse() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        var proof = proof(EmailPurpose.REGISTER);
        assertThrows(IllegalStateException.class, () -> codes.consume(proof));
        new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(proof));
        assertFault(400, () -> new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(proof)));
        assertNull(jdbc.queryForObject("SELECT code_hash FROM sc_email_challenge", String.class));
        assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.REGISTER, sender.code(EMAIL, EmailPurpose.REGISTER)));
    }

    @Test void failedAccountMutationRollsBackConsumptionAndPreservesProof() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        var proof = proof(EmailPurpose.REGISTER);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            codes.consume(proof);
            jdbc.update("INSERT INTO synthetic_account(id) VALUES(1)");
            throw new IllegalStateException("Synthetic account failure");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_account", Integer.class));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            codes.consume(proof);
            jdbc.update("INSERT INTO synthetic_account(id) VALUES(1)");
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_account", Integer.class));
    }

    @Test void fiveFailedAttemptsCommitEvenWhenCallerCatchesThemInsideRolledBackTransaction() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        String correct = sender.code(EMAIL, EmailPurpose.REGISTER);
        String wrong = (correct.charAt(0) == '0' ? "1" : "0") + correct.substring(1);
        for (int i = 0; i < 5; i++) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.REGISTER, wrong));
                status.setRollbackOnly();
            });
        }
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM sc_email_challenge", Integer.class));
        assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.REGISTER, correct));
    }

    @Test void codesAndProofsAreBoundToPurposeMailboxAndServiceIssuer() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        String code = sender.code(EMAIL, EmailPurpose.REGISTER);
        assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.RESET_PASSWORD, code));
        assertFault(400, () -> codes.verify("other@example.invalid", EmailPurpose.REGISTER, code));
        var proof = codes.verify(EMAIL, EmailPurpose.REGISTER, code);
        var otherIssuer = new EmailCodeService(jdbc, manager, clock, sender, SERVER_SECRET);
        assertFault(400, () -> new TransactionTemplate(manager).executeWithoutResult(status -> otherIssuer.consume(proof)));
    }

    @Test void replacementInvalidatesAlreadyVerifiedProofAndEnforcesResendCooldown() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        var oldProof = proof(EmailPurpose.REGISTER);
        assertFault(429, () -> codes.request(EMAIL, EmailPurpose.REGISTER));
        clock.advance(60_000);
        codes.request(EMAIL, EmailPurpose.REGISTER);
        assertFault(400, () -> new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(oldProof)));
        var latest = proof(EmailPurpose.REGISTER);
        new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(latest));
    }

    @Test void expiryAppliesBothToVerificationAndPreviouslyIssuedProof() {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        var proof = proof(EmailPurpose.REGISTER);
        clock.advance(600_000);
        assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.REGISTER, sender.code(EMAIL, EmailPurpose.REGISTER)));
        assertFault(400, () -> new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(proof)));
    }

    @Test void failedDeliveryIsUnusableAndRetainsCooldownWithoutSupplierExceptionLeak() {
        sender.fail = true;
        ApplicationFault failed = assertFault(503, () -> codes.request(EMAIL, EmailPurpose.REGISTER));
        assertFalse(failed.getMessage().contains("sensitive-supplier-diagnostic"));
        assertNull(failed.getCause());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM sc_email_challenge", String.class));
        assertNull(jdbc.queryForObject("SELECT code_hash FROM sc_email_challenge", String.class));
        assertFault(400, () -> codes.verify(EMAIL, EmailPurpose.REGISTER, sender.code(EMAIL, EmailPurpose.REGISTER)));
        assertFault(429, () -> codes.request(EMAIL, EmailPurpose.REGISTER));
    }

    @Test void dailySendBudgetDoesNotResetWithEveryResendAndResetsAfterOneDay() {
        for (int i = 0; i < 10; i++) {
            codes.request(EMAIL, EmailPurpose.RESET_PASSWORD);
            clock.advance(60_000);
        }
        assertFault(429, () -> codes.request(EMAIL, EmailPurpose.RESET_PASSWORD));
        clock.advance(24 * 60 * 60_000L);
        codes.request(EMAIL, EmailPurpose.RESET_PASSWORD);
        assertEquals(1, jdbc.queryForObject("SELECT sent_count FROM sc_email_challenge", Integer.class));
    }

    @Test void twoConcurrentAccountMutationsCanConsumeExactlyOnce() throws Exception {
        codes.request(EMAIL, EmailPurpose.REGISTER);
        var proof = proof(EmailPurpose.REGISTER);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> consumeAfterBarrier(proof, ready, start, successes, rejected));
            var second = executor.submit(() -> consumeAfterBarrier(proof, ready, start, successes, rejected));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, successes.get());
        assertEquals(1, rejected.get());
    }

    @Test void unavailableConfigurationAndUnsafeAddressesNeverCreateChallenges() {
        sender.enabled = false;
        assertFalse(codes.available());
        assertFault(503, () -> codes.request(EMAIL, EmailPurpose.REGISTER));
        sender.enabled = true;
        for (String address : new String[]{"a@example.invalid\r\nBCC:other@example.invalid", "a..b@example.invalid", "a@localhost", ".a@example.invalid", "a@", "a@@example.invalid"})
            assertFault(400, () -> codes.request(address, EmailPurpose.REGISTER));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_email_challenge", Integer.class));
        assertEquals("a@xn--fsqu00a.xn--fiqs8s", EmailCodeService.normalizeEmail(" A@例子.中国 "));
    }

    @Test void smtpCannotBeInvokedFromAnAccountTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> codes.request(EMAIL, EmailPurpose.REGISTER)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_email_challenge", Integer.class));
    }

    private void consumeAfterBarrier(EmailCodeService.Proof proof, CountDownLatch ready, CountDownLatch start,
                                     AtomicInteger successes, AtomicInteger rejected) {
        ready.countDown();
        try {
            if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
            new TransactionTemplate(manager).executeWithoutResult(status -> codes.consume(proof));
            successes.incrementAndGet();
        } catch (ApplicationFault failure) {
            if (failure.httpStatus() != 400) throw failure;
            rejected.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
    private EmailCodeService.Proof proof(EmailPurpose purpose) { return codes.verify(EMAIL, purpose, sender.code(EMAIL, purpose)); }
    private static ApplicationFault assertFault(int status, org.junit.jupiter.api.function.Executable action) {
        ApplicationFault failure = assertThrows(ApplicationFault.class, action);
        assertEquals(status, failure.httpStatus()); return failure;
    }
    private static final class FakeSender implements EmailCodeSender {
        final Map<String,String> codes = new ConcurrentHashMap<>();
        boolean enabled = true, fail;
        Runnable observe = () -> { };
        @Override public boolean available() { return enabled; }
        @Override public void send(String email, EmailPurpose purpose, String code) {
            observe.run(); codes.put(email + ':' + purpose, code);
            if (fail) throw new IllegalStateException("sensitive-supplier-diagnostic");
        }
        String code(String email, EmailPurpose purpose) { return codes.get(email + ':' + purpose); }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
