package io.studentcloud.tasks;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private TaskService tasks;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void isolatedDatabase() {
        var database = new DriverManagerDataSource("jdbc:h2:mem:tasks_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-tasks-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        clock = new MutableClock();
        tasks = new TaskService(jdbc, transactions, clock);
    }

    @Test
    void sameKeySameContentPersistsOneJobAndOneCreationEventAcrossServiceRestart() {
        var first = tasks.create(request("same-key", "{\"courseId\":1}"));
        var restarted = new TaskService(jdbc, transactions, clock);
        assertEquals(first.id(), restarted.create(request("same-key", "{\"courseId\":1}")).id());
        assertEquals(TaskState.QUEUED, restarted.get(10, first.id()).state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        assertEquals(1, restarted.pendingOutbox(20).size());
        assertFalse(restarted.pendingOutbox(20).getFirst().payload().contains("courseId"));
        assertEquals(first.id(), restarted.pendingOutbox(20).getFirst().taskId());
    }

    @Test
    void sameKeyDifferentPayloadOrCategoryConflictsButOtherOwnersDoNotCollide() {
        tasks.create(request("request-1", "first"));
        var failure = assertThrows(ApplicationFault.class, () -> tasks.create(request("request-1", "second")));
        assertEquals(409, failure.httpStatus());
        assertEquals("TASK_IDEMPOTENCY_CONFLICT", failure.code());
        assertThrows(ApplicationFault.class, () -> tasks.create(new CreateTask(10, 20, TaskType.FILE_ORGANIZE, "first", "request-1")));
        var other = tasks.create(new CreateTask(11, 20, TaskType.DOCUMENT_INDEX, "first", "request-1"));
        assertEquals(11, other.actorId());
        assertEquals(2, tasks.pendingOutbox(20).size());
    }

    @Test
    void creationEventFailureRollsBackTheJob() {
        jdbc.execute("DROP TABLE sc_job_outbox");
        assertThrows(RuntimeException.class, () -> tasks.create(request("event-failure", "small-payload")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
    }

    @Test
    void joinedCreationRequiresATransactionAndRollsBackWithItsOwningRecord() {
        var request = request("joined-create", "small-payload");
        assertThrows(IllegalTransactionStateException.class, () -> tasks.createInCurrentTransaction(request));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        var outer = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
            var created = tasks.createInCurrentTransaction(request);
            assertEquals(created.id(), tasks.createInCurrentTransaction(request).id());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
            assertEquals(1, tasks.pendingOutbox(20).size());
            throw new IllegalStateException("owning record failed");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        assertTrue(tasks.pendingOutbox(20).isEmpty());
        var retried = outer.execute(status -> tasks.createInCurrentTransaction(request));
        assertNotNull(retried);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        assertEquals(1, tasks.pendingOutbox(20).size());
    }

    @Test
    void joinedDuplicateConflictMarksTheOuterTransactionRollbackOnlyEvenIfCaught() {
        // Force an insertion-time duplicate after the idempotency lookup, as a racing commit would.
        jdbc.execute("ALTER TABLE sc_job ADD CONSTRAINT one_job_per_actor UNIQUE (actor_id)");
        var outer = new TransactionTemplate(transactions);
        assertThrows(UnexpectedRollbackException.class, () -> outer.executeWithoutResult(status -> {
            tasks.createInCurrentTransaction(request("first-joined", "first"));
            var conflict = assertThrows(ApplicationFault.class,
                    () -> tasks.createInCurrentTransaction(request("raced-joined", "second")));
            assertEquals("TASK_CREATE_CONFLICT", conflict.code());
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        assertTrue(tasks.pendingOutbox(20).isEmpty());
    }

    @Test
    void publicCreationStillCommitsIndependentlyOfAnOuterRollback() {
        var outer = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
            tasks.create(request("independent-create", "small-payload"));
            throw new IllegalStateException("unrelated caller rollback");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc_job", Integer.class));
        assertEquals(1, tasks.pendingOutbox(20).size());
    }

    @Test
    void expiredLeaseCannotHeartbeatOrCompleteAndReclaimedFenceRejectsOldWorker() {
        var job = tasks.create(request("lease-case", "{}"));
        var old = tasks.claim("worker-a", 5).orElseThrow();
        clock.advance(Duration.ofSeconds(5));
        assertEquals("TASK_LEASE_LOST", assertThrows(ApplicationFault.class, () -> tasks.heartbeat(old, 5)).code());
        assertThrows(ApplicationFault.class, () -> tasks.complete(old, "stale result"));
        var replacement = tasks.claim("worker-b", 30).orElseThrow();
        assertEquals(job.id(), replacement.id());
        assertEquals(2, replacement.task().attempts());
        assertTrue(replacement.fence() > old.fence());
        assertThrows(ApplicationFault.class, () -> tasks.fail(old, "STALE_WORKER", true, Duration.ZERO));
        var done = tasks.complete(replacement, "new result");
        assertEquals(TaskState.SUCCEEDED, done.state());
        assertEquals("new result", tasks.get(10, job.id()).result());
        assertTrue(tasks.claim("worker-a", 5).isEmpty());
    }

    @Test
    void ownerCancellationInvalidatesRunningFenceAndDoesNotLeakOtherOwners() {
        var job = tasks.create(request("cancel-case", "{}"));
        var lease = tasks.claim("worker-a", 30).orElseThrow();
        assertEquals(404, assertThrows(ApplicationFault.class, () -> tasks.cancel(11, job.id())).httpStatus());
        assertEquals(TaskState.RUNNING, tasks.get(10, job.id()).state());
        assertEquals(TaskState.CANCELLED, tasks.cancel(10, job.id()).state());
        assertEquals(TaskState.CANCELLED, tasks.cancel(10, job.id()).state());
        assertThrows(ApplicationFault.class, () -> tasks.complete(lease, "too late"));
        assertThrows(ApplicationFault.class, () -> tasks.heartbeat(lease, 30));
        assertTrue(tasks.list(11, 0, 20).isEmpty());
        assertEquals(404, assertThrows(ApplicationFault.class, () -> tasks.get(11, job.id())).httpStatus());
    }

    @Test
    void retryDelayAndMaximumAttemptsAreDurableAndBounded() {
        var job = tasks.create(request("retry-case", "{}"));
        var first = tasks.claim("worker", 30).orElseThrow();
        assertEquals(TaskState.QUEUED, tasks.fail(first, "TEMPORARILY_UNAVAILABLE", true, Duration.ofSeconds(10)).state());
        assertTrue(tasks.claim("worker", 30).isEmpty());
        clock.advance(Duration.ofSeconds(10));
        var second = tasks.claim("worker", 30).orElseThrow();
        assertEquals(TaskState.QUEUED, tasks.fail(second, "TEMPORARILY_UNAVAILABLE", true, Duration.ZERO).state());
        var third = tasks.claim("worker", 30).orElseThrow();
        assertEquals(3, third.task().attempts());
        assertEquals(TaskState.FAILED, tasks.fail(third, "TEMPORARILY_UNAVAILABLE", true, Duration.ZERO).state());
        assertTrue(tasks.claim("worker", 30).isEmpty());
        assertEquals(3, tasks.get(10, job.id()).attempts());
    }

    @Test
    void repeatedProcessLossEndsAfterMaximumLeases() {
        var job = tasks.create(request("lost-process", "{}"));
        for (int attempt = 1; attempt <= TaskService.MAX_ATTEMPTS; attempt++) {
            assertEquals(attempt, tasks.claim("worker", 5).orElseThrow().task().attempts());
            clock.advance(Duration.ofSeconds(5));
        }
        assertTrue(tasks.claim("worker", 5).isEmpty());
        assertEquals(TaskState.FAILED, tasks.get(10, job.id()).state());
        assertEquals("LEASE_ATTEMPTS_EXHAUSTED", tasks.get(10, job.id()).failureCode());
    }

    @Test
    void outboxDeliveryIsExplicitAndIdempotent() {
        tasks.create(request("outbox-case", "{}"));
        var event = tasks.pendingOutbox(10).getFirst();
        assertTrue(tasks.markOutboxPublished(event.id()));
        assertFalse(tasks.markOutboxPublished(event.id()));
        assertTrue(tasks.pendingOutbox(10).isEmpty());
    }

    @Test
    void payloadBoundsAreUtf8BytesAndFailuresNeverPersistRawExceptionText() {
        assertThrows(ApplicationFault.class, () -> tasks.create(request("unicode", "课".repeat(12_000))));
        var job = tasks.create(request("safe-failure", "{}"));
        var lease = tasks.claim("worker", 30).orElseThrow();
        assertThrows(ApplicationFault.class, () -> tasks.fail(lease, "Exception: private-url", false, Duration.ZERO));
        assertThrows(ApplicationFault.class, () -> tasks.complete(lease, "x".repeat(TaskService.MAX_RESULT_BYTES + 1)));
        assertEquals(TaskState.RUNNING, tasks.get(10, job.id()).state());
        assertEquals(TaskState.FAILED, tasks.fail(lease, "INVALID_INPUT", false, Duration.ZERO).state());
    }

    private static CreateTask request(String key, String payload) {
        return new CreateTask(10, 20, TaskType.DOCUMENT_INDEX, payload, key);
    }

    private static final class MutableClock extends Clock {
        private long now = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli();
        void advance(Duration duration) { now += duration.toMillis(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
