package io.studentcloud.drive.application;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class QuotaLedgerTest {
    private JdbcTemplate jdbc;
    private QuotaLedger ledger;
    private final QuotaLedger.Scope scope = new QuotaLedger.Scope(7, 3, "personal-root-v1");

    @BeforeEach void setup() {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:quota_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-quota-schema.sql")).execute(db);
        jdbc = new JdbcTemplate(db);
        ledger = new QuotaLedger(jdbc, new DataSourceTransactionManager(db), Clock.systemUTC());
    }

    @Test void unknownUsageIsNotZeroAndCannotReserveUntilReconciled() {
        var view = ledger.view(scope, 200L);
        assertEquals("UNKNOWN", view.usageState());
        assertNull(view.usedBytes());
        assertNull(view.availableBytes());
        assertFault("QUOTA_RECONCILE_REQUIRED", () -> ledger.reserve(scope, 200L, "upload-one", 128));
    }

    @Test void concurrentReservationsCannotOverbookAndSettlementIsIdempotent() throws Exception {
        ledger.reconcile(scope, 200L, () -> 0L);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> first = () -> { gate.await(); try { return ledger.reserve(scope, 200L, Thread.currentThread().getName(), 128).id(); }
                catch (ApplicationFault fault) { assertEquals("DRIVE_QUOTA_EXCEEDED", fault.code()); return null; } };
            var a = pool.submit(first); var b = pool.submit(first); gate.countDown();
            String idA = a.get(15, TimeUnit.SECONDS), idB = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(idA == null, idB == null);
            String id = idA != null ? idA : idB;
            ledger.settle(scope, id, 100); ledger.settle(scope, id, 100);
            assertEquals(100, ledger.view(scope, 200L).usedBytes());
            assertEquals(0, ledger.view(scope, 200L).reservedBytes());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_quota_entry WHERE category='WRITE'", Integer.class));
            assertFault("QUOTA_CONFLICT", () -> ledger.settle(scope, id, 101));
        }
    }

    @Test void repeatedOperationChecksParametersAndForeignOwnerCannotSettle() {
        ledger.reconcile(scope, 200L, () -> 10L);
        var one = ledger.reserve(scope, 200L, "same-operation", 120);
        assertEquals(one.id(), ledger.reserve(scope, 200L, "same-operation", 120).id());
        assertEquals(120, ledger.view(scope, 200L).reservedBytes());
        assertFault("QUOTA_CONFLICT", () -> ledger.reserve(scope, 200L, "same-operation", 121));
        var other = new QuotaLedger.Scope(7, 4, "personal-root-v1");
        assertFault("QUOTA_NOT_FOUND", () -> ledger.settle(other, one.id(), 100));
        ledger.releaseConfirmedFailure(scope, one.id()); ledger.releaseConfirmedFailure(scope, one.id());
        assertEquals(10, ledger.view(scope, 200L).usedBytes());
        assertEquals(0, ledger.view(scope, 200L).reservedBytes());
        assertFault("QUOTA_CONFLICT", () -> ledger.settle(scope, one.id(), 100));
    }

    @Test void unresolvedReservationSurvivesExpiryAndBlocksReconciliation() {
        ledger.reconcile(scope, 200L, () -> 30L);
        var held = ledger.reserve(scope, 200L, "unknown-publication", 150);
        jdbc.update("UPDATE sc_quota_reservation SET expires_at=0 WHERE id=?", held.id());
        assertEquals(150, ledger.view(scope, 200L).reservedBytes());
        assertFault("QUOTA_PENDING", () -> ledger.reconcile(scope, 200L, () -> 180L));
        assertEquals(30, ledger.view(scope, 200L).usedBytes());
    }

    @Test void zeroByteMutationStillBlocksReconciliationUntilConfirmed() {
        ledger.reconcile(scope,200L,()->30L);
        var held=ledger.reserve(scope,200L,"purge",0);
        assertFault("QUOTA_PENDING",()->ledger.reconcile(scope,200L,()->30L));
        ledger.settle(scope,held.id(),0);
        assertEquals(10L,ledger.reconcile(scope,200L,()->10L).usedBytes());
    }

    @Test void zeroByteMutationDoesNotInventKnownUsageForAnUnscannedDirectory() {
        var held=ledger.reserve(scope,200L,"mkdir",0);
        ledger.settle(scope,held.id(),0);
        assertEquals("UNKNOWN",ledger.view(scope,200L).usageState());
        assertNull(ledger.view(scope,200L).usedBytes());
    }

    @Test void loweringQuotaDoesNotDeleteOrReduceRecordedUsageAndArithmeticCannotWrap() {
        ledger.reconcile(scope, 200L, () -> 180L);
        assertEquals(0, ledger.view(scope, 100L).availableBytes());
        assertEquals(180, ledger.view(scope, 100L).usedBytes());
        assertFault("DRIVE_QUOTA_EXCEEDED", () -> ledger.reserve(scope, 100L, "over", 1));
        ledger.reconcile(scope, null, () -> Long.MAX_VALUE - 2);
        assertFault("QUOTA_OVERFLOW", () -> ledger.reserve(scope, null, "overflow", 3));
    }

    private static void assertFault(String code, Runnable run) {
        assertEquals(code, assertThrows(ApplicationFault.class, run::run).code());
    }
}
