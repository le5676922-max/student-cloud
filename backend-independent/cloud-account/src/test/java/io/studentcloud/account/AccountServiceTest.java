package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {
    private static final String PASSWORD = "Synthetic-password-123";
    private JdbcTemplate jdbc;
    private AccountService accounts;
    private MutableClock clock;

    @BeforeEach
    void database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:student_cloud_independent_account_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        accounts = new AccountService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), clock, Duration.ofHours(1));
    }

    @AfterEach
    void closeOnlyTheGeneratedInMemoryDatabase() { jdbc.execute("SHUTDOWN"); }

    @Test
    void uninstalledPlatformHasNoImplicitAccountOrDefaultPassword() {
        assertFalse(accounts.installationStatus());
        assertEquals("Student Cloud", accounts.siteName());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Integer.class));
        assertFault("30000", () -> accounts.login("admin", "admin"));
    }

    @Test
    void initializationCreatesOneGlobalAdminWithGeneratedOpaqueAccountId() {
        AccountView view = install();
        assertTrue(accounts.installationStatus());
        assertEquals("Synthetic study space", accounts.siteName());
        assertEquals(Role.ADMIN, view.role());
        assertTrue(view.enabled());
        assertNull(view.workspaceId());
        assertTrue(view.accountId().matches("SC[0-9A-F]{32}"));
        assertTrue(view.id() > 0);
    }

    @Test
    void repeatedInitializationCannotReplaceTheAdministrator() {
        AccountView first = install();
        assertFault("41000", () -> accounts.install("Replacement", "replacement", "Other-password-123"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Integer.class));
        assertEquals(first.id(), accounts.login("synthetic-admin", PASSWORD).account().id());
        assertEquals("Synthetic study space", accounts.siteName());
    }

    @Test
    void invalidInitializationLeavesEveryTableUntouched() {
        assertFault("41000", () -> accounts.install("Site", "aa", PASSWORD));
        assertFault("41000", () -> accounts.install("Site", "valid-name", "short"));
        assertFault("41000", () -> accounts.install("\u0000site", "valid-name", PASSWORD));
        assertFault("41000", () -> accounts.install("Site", "SC0123456789ABCDEF", PASSWORD));
        assertFalse(accounts.installationStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Integer.class));
    }

    @Test
    void twoIndependentInitializersContendOnTheDatabaseRowWithExactlyOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int number = i;
                futures.add(pool.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        // A distinct service proves initialization does not depend on an object monitor.
                        var source = jdbc.getDataSource();
                        var contender = new AccountService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), clock, Duration.ofHours(1));
                        contender.install("Site " + number, "admin-" + number, PASSWORD);
                        winners.incrementAndGet();
                    } catch (ApplicationFault expected) {
                        assertEquals("41000", expected.code());
                        conflicts.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
            assertEquals(1, conflicts.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void usernameLoginNormalizesWhitespaceAndCase() {
        install();
        LoginResult result = accounts.login("  SYNTHETIC-ADMIN  ", PASSWORD);
        assertEquals("synthetic-admin", result.account().username());
        assertEquals(Role.ADMIN, accounts.authenticate(result.token()).role());
    }

    @Test
    void generatedAccountIdIsAlsoALoginIdentifier() {
        AccountView account = install();
        assertEquals(account.id(), accounts.login(account.accountId().toLowerCase(), PASSWORD).account().id());
    }

    @Test
    void emailLoginResolvesOnlyTheDatabaseAssignedAddress() {
        AccountView account = install();
        jdbc.update("UPDATE sc_account SET email=? WHERE id=?", "synthetic@example.invalid", account.id());
        assertEquals(account.id(), accounts.login(" SYNTHETIC@EXAMPLE.INVALID ", PASSWORD).account().id());
        assertFault("30000", () -> accounts.login("other@example.invalid", PASSWORD));
    }

    @Test
    void unknownAccountAndWrongPasswordReturnTheSamePublicFailure() {
        install();
        ApplicationFault wrong = assertThrows(ApplicationFault.class, () -> accounts.login("synthetic-admin", "Wrong-password-123"));
        ApplicationFault unknown = assertThrows(ApplicationFault.class, () -> accounts.login("unknown", PASSWORD));
        assertEquals(wrong.code(), unknown.code());
        assertEquals(wrong.getMessage(), unknown.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_session", Integer.class));
    }

    @Test
    void disabledAccountCannotLoginAndPreviouslyIssuedSessionIsImmediatelyRejected() {
        AccountView account = install();
        String token = accounts.login(account.username(), PASSWORD).token();
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=?", account.id());
        assertFault("30000", () -> accounts.login(account.username(), PASSWORD));
        assertFault("42000", () -> accounts.authenticate(token));
    }

    @Test
    void plaintextTokensAndPasswordsAreNeverPersisted() {
        install();
        LoginResult session = accounts.login("synthetic-admin", PASSWORD);
        String encoded = jdbc.queryForObject("SELECT password_hash FROM sc_account", String.class);
        String digest = jdbc.queryForObject("SELECT token_hash FROM sc_session", String.class);
        assertNotNull(encoded);
        assertTrue(encoded.startsWith("pbkdf2-sha256$310000$"));
        assertFalse(encoded.contains(PASSWORD));
        assertNotEquals(session.token(), digest);
        assertNotNull(digest);
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertFalse(session.account().toString().contains(encoded));
        assertFalse(accounts.authenticate(session.token()).toString().contains(encoded));
    }

    @Test
    void logoutIsIdempotentAndDoesNotRevokeOtherSessions() {
        install();
        LoginResult first = accounts.login("synthetic-admin", PASSWORD);
        LoginResult second = accounts.login("synthetic-admin", PASSWORD);
        assertNotEquals(first.token(), second.token());
        accounts.logout(first.token());
        accounts.logout(first.token());
        accounts.logout(null);
        assertFault("42000", () -> accounts.authenticate(first.token()));
        assertNotNull(accounts.authenticate(second.token()));
    }

    @Test
    void sessionExpiresAtTheExactInjectedClockBoundary() {
        install();
        LoginResult session = accounts.login("synthetic-admin", PASSWORD);
        clock.now.set(session.expiresAt().minusMillis(1));
        assertNotNull(accounts.authenticate(session.token()));
        clock.now.set(session.expiresAt());
        assertFault("42000", () -> accounts.authenticate(session.token()));
    }

    @Test
    void passwordChangeRevokesAllExistingTokensAndAcceptsOnlyTheNewPassword() {
        AccountView account = install();
        LoginResult first = accounts.login(account.username(), PASSWORD);
        LoginResult second = accounts.login(account.username(), PASSWORD);
        accounts.changePassword(account.id(), PASSWORD, "Replacement-password-456");
        assertFault("42000", () -> accounts.authenticate(first.token()));
        assertFault("42000", () -> accounts.authenticate(second.token()));
        assertFault("30000", () -> accounts.login(account.username(), PASSWORD));
        assertEquals(account.id(), accounts.login(account.username(), "Replacement-password-456").account().id());
        assertEquals(2L, jdbc.queryForObject("SELECT session_version FROM sc_account", Long.class));
    }

    @Test
    void wrongOldPasswordDoesNotMutatePasswordOrSessions() {
        AccountView account = install();
        LoginResult session = accounts.login(account.username(), PASSWORD);
        String encoded = jdbc.queryForObject("SELECT password_hash FROM sc_account", String.class);
        assertFault("30000", () -> accounts.changePassword(account.id(), "Wrong-password-123", "Replacement-password-456"));
        assertEquals(encoded, jdbc.queryForObject("SELECT password_hash FROM sc_account", String.class));
        assertNotNull(accounts.authenticate(session.token()));
    }

    @Test
    void sessionVersionRevocationUsesTheLiveRowEvenWhenTheTokenStillExists() {
        AccountView account = install();
        LoginResult session = accounts.login(account.username(), PASSWORD);
        jdbc.update("UPDATE sc_account SET session_version=session_version+1 WHERE id=?", account.id());
        assertFault("42000", () -> accounts.authenticate(session.token()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_session", Integer.class));
    }

    @Test
    void workspaceDeactivationRevokesMemberAccessWithoutClientSuppliedRoles() {
        AccountView account = install();
        LoginResult session = accounts.login(account.username(), PASSWORD);
        jdbc.update("INSERT INTO sc_workspace(name,enabled,created_at) VALUES('Synthetic workspace',TRUE,?)", clock.millis());
        Long workspace = jdbc.queryForObject("SELECT id FROM sc_workspace", Long.class);
        jdbc.update("UPDATE sc_account SET role='USER',workspace_id=? WHERE id=?", workspace, account.id());
        assertEquals(Role.USER, accounts.authenticate(session.token()).role());
        jdbc.update("UPDATE sc_workspace SET enabled=FALSE WHERE id=?", workspace);
        assertFault("42000", () -> accounts.authenticate(session.token()));
        assertFault("30000", () -> accounts.login(account.username(), PASSWORD));
    }

    @Test
    void ambiguousCrossColumnLoginAliasIsRejectedInsteadOfChoosingAnAccount() {
        AccountView account = install();
        String encoded = jdbc.queryForObject("SELECT password_hash FROM sc_account WHERE id=?", String.class, account.id());
        jdbc.update("INSERT INTO sc_account(account_id,username,email,password_hash,role,enabled,session_version,created_at) VALUES(?,?,?,?, 'ADMIN',TRUE,1,?)",
                "SC00000000000000000000000000000000", "other-account", account.username(), encoded, clock.millis());
        assertFault("30000", () -> accounts.login(account.username(), PASSWORD));
    }

    @Test
    void corruptPasswordEncodingFailsClosedWithoutUnboundedWork() {
        AccountView account = install();
        jdbc.update("UPDATE sc_account SET password_hash=? WHERE id=?", "pbkdf2-sha256$2147483647$AA==$AA==", account.id());
        assertFault("30000", () -> accounts.login(account.username(), PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_session", Integer.class));
    }

    @Test
    void malformedOrMissingTokensNeverAuthenticate() {
        install();
        for (String token : List.of("", "x", "a".repeat(44), "!".repeat(43), "a".repeat(43)))
            assertFault("42000", () -> accounts.authenticate(token));
        assertFault("42000", () -> accounts.authenticate(null));
    }

    @Test
    void independentPasswordHasherUsesRandomSaltAndRejectsMalformedHashes() {
        PasswordHasher hasher = new PasswordHasher();
        String first = hasher.hash(PASSWORD);
        String second = hasher.hash(PASSWORD);
        assertNotEquals(first, second);
        assertTrue(hasher.verify(PASSWORD, first));
        assertFalse(hasher.verify("Wrong-password-123", first));
        assertFalse(hasher.verify(PASSWORD, "md5$legacy"));
        assertFalse(hasher.verify(PASSWORD, "pbkdf2-sha256$1$AA==$AA=="));
        assertFalse(hasher.verify(PASSWORD, first + "$trailing"));
        assertFalse(hasher.verify("x".repeat(129), first));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("short"));
    }

    @Test
    void existingAccountDataPreventsAccidentalReinitialization() {
        install();
        jdbc.update("UPDATE sc_platform_settings SET installed=FALSE WHERE id=1");
        assertFault("41000", () -> accounts.install("Replacement", "replacement", PASSWORD));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Integer.class));
    }

    @Test
    void unsupportedSessionDurationsAreRejectedBeforeDatabaseUse() {
        var source = jdbc.getDataSource();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(IllegalArgumentException.class, () -> new AccountService(jdbc, transactions, clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new AccountService(jdbc, transactions, clock, Duration.ofDays(31)));
    }

    private AccountView install() { return accounts.install("Synthetic study space", "synthetic-admin", PASSWORD); }

    private static void assertFault(String code, Runnable task) {
        assertEquals(code, assertThrows(ApplicationFault.class, task::run).code());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
