package io.studentcloud.tasks;

import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-backed task state machine. This module does not start a scheduler or execute tools.
 * A successful claim only grants a bounded lease; workers must recheck authorization and fence
 * external side effects separately. State fencing alone cannot stop a stale filesystem write.
 */
@Service
public final class TaskService {
    public static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    public static final int MAX_RESULT_BYTES = 16 * 1024;
    public static final int MAX_ATTEMPTS = 3;
    private static final int MAX_LEASE_SECONDS = 300;
    private static final String COLUMNS = "job_id,actor_id,workspace_id,task_type,payload,state,attempt_count,max_attempts,"
            + "available_at,worker_id,lease_until,fence,result_payload,failure_code,created_at,updated_at,finished_at";
    private static final RowMapper<TaskRecord> ROW = (row, index) -> new TaskRecord(
            row.getString("job_id"), row.getLong("actor_id"), row.getLong("workspace_id"),
            TaskType.valueOf(row.getString("task_type")), row.getString("payload"), TaskState.valueOf(row.getString("state")),
            row.getInt("attempt_count"), row.getInt("max_attempts"), row.getLong("available_at"),
            row.getString("worker_id"), nullableLong(row, "lease_until"), row.getLong("fence"),
            row.getString("result_payload"), row.getString("failure_code"), row.getLong("created_at"),
            row.getLong("updated_at"), nullableLong(row, "finished_at"));
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate joinedCreation;

    public TaskService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(10);
        joinedCreation = new TransactionTemplate(manager);
        joinedCreation.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** Actor/workspace-scoped idempotency; exact payload and category are bound by a digest. */
    public TaskRecord create(CreateTask request) {
        Creation creation = validateCreation(request);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                TaskRecord existing = existing(creation.scope(), creation.digest());
                return existing != null ? existing : insert(request, creation);
            }));
        } catch (DuplicateKeyException raced) {
            // Use a fresh transaction even if the caller has a repeatable-read snapshot.
            return Objects.requireNonNull(transactions.execute(status -> {
                TaskRecord winner = existing(creation.scope(), creation.digest());
                if (winner != null) return winner;
                throw creationConflict();
            }));
        }
    }

    /** Atomic caller-owned creation, for a task whose owning record must commit with its outbox event. */
    public TaskRecord createInCurrentTransaction(CreateTask request) {
        return Objects.requireNonNull(joinedCreation.execute(status -> {
            Creation creation = validateCreation(request);
            TaskRecord existing = existing(creation.scope(), creation.digest());
            if (existing != null) return existing;
            try { return insert(request, creation); }
            catch (DuplicateKeyException raced) {
                // A joined repeatable-read snapshot may not see the winning commit. Propagate and
                // mark the whole caller transaction rollback-only; retry in a fresh transaction.
                throw creationConflict();
            }
        }));
    }

    private static Creation validateCreation(CreateTask request) {
        if (request == null || request.type() == null) throw invalid("请选择任务类型");
        requireIdentity(request.actorId(), request.workspaceId());
        bounded(request.payload(), MAX_PAYLOAD_BYTES, "任务内容不能为空或超过 32 KiB");
        if (request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw invalid("幂等标识格式不正确");
        String scope = hash(request.actorId() + "\n" + request.workspaceId() + "\n" + request.idempotencyKey());
        String digest = hash(request.type().name() + "\n" + request.payload());
        return new Creation(scope, digest);
    }

    private TaskRecord insert(CreateTask request, Creation creation) {
        long now = clock.millis();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sc_job (job_id,actor_id,workspace_id,task_type,payload,payload_digest,"
                        + "idempotency_hash,state,attempt_count,max_attempts,available_at,fence,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,'QUEUED',0,?,?,0,?,?)",
                id, request.actorId(), request.workspaceId(), request.type().name(), request.payload(), creation.digest(),
                creation.scope(), MAX_ATTEMPTS, now, now, now);
        String event = "{\"taskId\":\"" + id + "\",\"actorId\":" + request.actorId()
                + ",\"workspaceId\":" + request.workspaceId() + ",\"type\":\"" + request.type().name() + "\"}";
        jdbc.update("INSERT INTO sc_job_outbox (event_id,job_id,event_type,event_payload,created_at) VALUES (?,?,'TASK_CREATED',?,?)",
                UUID.randomUUID().toString(), id, event, now);
        return required(id);
    }

    /** Internal worker API. It must never be exposed as an unauthenticated public endpoint. */
    public Optional<TaskLease> claim(String workerId, int leaseSeconds) {
        requireWorker(workerId);
        requireLease(leaseSeconds);
        return Objects.requireNonNull(transactions.execute(status -> {
            long now = clock.millis();
            jdbc.update("UPDATE sc_job SET state='FAILED',failure_code='LEASE_ATTEMPTS_EXHAUSTED',worker_id=NULL,"
                            + "lease_until=NULL,fence=fence+1,updated_at=?,finished_at=? WHERE state='RUNNING' "
                            + "AND lease_until<=? AND attempt_count>=max_attempts", now, now, now);
            List<TaskRecord> candidates = jdbc.query("SELECT " + COLUMNS + " FROM sc_job WHERE attempt_count<max_attempts "
                            + "AND ((state='QUEUED' AND available_at<=?) OR (state='RUNNING' AND lease_until<=?)) "
                            + "ORDER BY available_at,created_at,job_id LIMIT 16", ROW, now, now);
            for (TaskRecord task : candidates) {
                int changed = jdbc.update("UPDATE sc_job SET state='RUNNING',worker_id=?,lease_until=?,fence=fence+1,"
                                + "attempt_count=attempt_count+1,updated_at=?,failure_code=NULL WHERE job_id=? AND fence=? "
                                + "AND attempt_count=? AND attempt_count<max_attempts AND "
                                + "((state='QUEUED' AND available_at<=?) OR (state='RUNNING' AND lease_until<=?))",
                        workerId, now + leaseSeconds * 1000L, now, task.id(), task.fence(), task.attempts(), now, now);
                if (changed == 1) return Optional.of(new TaskLease(required(task.id())));
            }
            return Optional.<TaskLease>empty();
        }));
    }

    /** An expired lease cannot heartbeat back to life, even before another worker claims it. */
    public TaskLease heartbeat(TaskLease lease, int leaseSeconds) {
        requireLeaseToken(lease);
        requireLease(leaseSeconds);
        return Objects.requireNonNull(transactions.execute(status -> {
            long now = clock.millis();
            int changed = jdbc.update("UPDATE sc_job SET lease_until=?,updated_at=? WHERE job_id=? AND worker_id=? "
                            + "AND fence=? AND state='RUNNING' AND lease_until>?",
                    now + leaseSeconds * 1000L, now, lease.id(), lease.workerId(), lease.fence(), now);
            if (changed != 1) throw staleLease();
            return new TaskLease(required(lease.id()));
        }));
    }

    public TaskRecord complete(TaskLease lease, String result) {
        return finish(lease, result, true);
    }

    /** A definitive external execution may contain partial file changes while the job is failed. */
    public TaskRecord finish(TaskLease lease, String result, boolean success) {
        requireLeaseToken(lease);
        if (result != null && result.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES)
            throw invalid("任务结果超过 16 KiB");
        return Objects.requireNonNull(transactions.execute(status -> {
            long now = clock.millis();
            int changed = jdbc.update("UPDATE sc_job SET state=?,result_payload=?,failure_code=?,worker_id=NULL,"
                            + "lease_until=NULL,fence=fence+1,updated_at=?,finished_at=? WHERE job_id=? AND worker_id=? "
                            + "AND fence=? AND state='RUNNING' AND lease_until>?",
                    success ? TaskState.SUCCEEDED.name() : TaskState.FAILED.name(), result,
                    success ? null : "TOOL_EXECUTION_FAILED", now, now, lease.id(), lease.workerId(), lease.fence(), now);
            if (changed != 1) throw staleLease();
            return required(lease.id());
        }));
    }

    /** Safe public failure codes only. Do not persist exception messages, keys, or private URLs. */
    public TaskRecord fail(TaskLease lease, String failureCode, boolean retryable, Duration retryAfter) {
        requireLeaseToken(lease);
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid("任务失败原因格式不正确");
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.compareTo(Duration.ofHours(1)) > 0)
            throw invalid("任务重试间隔不正确");
        return Objects.requireNonNull(transactions.execute(status -> {
            long now = clock.millis();
            TaskRecord current = required(lease.id());
            boolean retry = retryable && current.attempts() < current.maxAttempts();
            int changed = jdbc.update("UPDATE sc_job SET state=?,failure_code=?,worker_id=NULL,lease_until=NULL,"
                            + "fence=fence+1,available_at=?,updated_at=?,finished_at=? WHERE job_id=? AND worker_id=? "
                            + "AND fence=? AND state='RUNNING' AND lease_until>?",
                    retry ? TaskState.QUEUED.name() : TaskState.FAILED.name(), failureCode, now + retryAfter.toMillis(),
                    now, retry ? null : now, lease.id(), lease.workerId(), lease.fence(), now);
            if (changed != 1) throw staleLease();
            return required(lease.id());
        }));
    }

    /** Owner-scoped cancellation; a cancelled lease cannot commit any later task-state change. */
    public TaskRecord cancel(long actorId, String jobId) {
        requireActor(actorId);
        requireId(jobId);
        return Objects.requireNonNull(transactions.execute(status -> {
            long now = clock.millis();
            jdbc.update("UPDATE sc_job SET state='CANCELLED',worker_id=NULL,lease_until=NULL,fence=fence+1,"
                            + "updated_at=?,finished_at=? WHERE job_id=? AND actor_id=? AND state IN ('QUEUED','RUNNING')",
                    now, now, jobId, actorId);
            TaskRecord current = owned(actorId, jobId);
            if (current.state() != TaskState.CANCELLED) throw new ApplicationFault("TASK_FINISHED", 409, "任务已经结束，无法取消");
            return current;
        }));
    }

    public TaskRecord get(long actorId, String jobId) {
        requireActor(actorId);
        requireId(jobId);
        return owned(actorId, jobId);
    }

    public List<TaskRecord> list(long actorId, int offset, int limit) {
        requireActor(actorId);
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid("任务分页参数不正确");
        return jdbc.query("SELECT " + COLUMNS + " FROM sc_job WHERE actor_id=? ORDER BY created_at DESC,job_id DESC LIMIT ? OFFSET ?",
                ROW, actorId, limit, offset);
    }

    /** Internal at-least-once outbox delivery API. Consumers deduplicate on event ID. */
    public List<TaskOutboxRecord> pendingOutbox(int limit) {
        if (limit < 1 || limit > 100) throw invalid("事件批量大小不正确");
        return jdbc.query("SELECT event_id,job_id,event_type,event_payload,created_at FROM sc_job_outbox "
                        + "WHERE published_at IS NULL ORDER BY created_at,event_id LIMIT ?",
                (row, index) -> new TaskOutboxRecord(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getLong(5)), limit);
    }

    public boolean markOutboxPublished(String eventId) {
        requireId(eventId);
        return jdbc.update("UPDATE sc_job_outbox SET published_at=? WHERE event_id=? AND published_at IS NULL", clock.millis(), eventId) == 1;
    }

    /** Local worker acknowledges creation after it actually claims the durable job. */
    public void acknowledgeClaimedTask(String taskId) {
        requireId(taskId);
        jdbc.update("UPDATE sc_job_outbox SET published_at=? WHERE job_id=? AND published_at IS NULL", clock.millis(), taskId);
    }

    private TaskRecord existing(String scope, String digest) {
        List<String> ids = jdbc.query("SELECT job_id,payload_digest FROM sc_job WHERE idempotency_hash=?", (row, index) -> {
            if (!digest.equals(row.getString("payload_digest")))
                throw new ApplicationFault("TASK_IDEMPOTENCY_CONFLICT", 409, "同一个幂等标识不能提交不同任务");
            return row.getString("job_id");
        }, scope);
        return ids.isEmpty() ? null : required(ids.getFirst());
    }

    private TaskRecord required(String jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sc_job WHERE job_id=?", ROW, jobId).stream().findFirst()
                .orElseThrow(TaskService::notFound);
    }

    private TaskRecord owned(long actorId, String jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sc_job WHERE job_id=? AND actor_id=?", ROW, jobId, actorId).stream()
                .findFirst().orElseThrow(TaskService::notFound);
    }

    private static Long nullableLong(ResultSet row, String field) throws SQLException {
        long value = row.getLong(field);
        return row.wasNull() ? null : value;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void bounded(String value, int bytes, String message) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > bytes) throw invalid(message);
    }

    private static void requireIdentity(long actor, long workspace) {
        requireActor(actor);
        // Zero denotes an account-owned personal drive; positive IDs denote a workspace.
        if (workspace < 0) throw invalid("工作空间标识不正确");
    }

    private static void requireActor(long actor) { if (actor <= 0) throw invalid("账号标识不正确"); }
    private static void requireId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw notFound();
    }
    private static void requireWorker(String worker) {
        if (worker == null || !worker.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) throw invalid("执行节点标识不正确");
    }
    private static void requireLease(int seconds) {
        if (seconds < 5 || seconds > MAX_LEASE_SECONDS) throw invalid("任务租约须为 5 至 300 秒");
    }
    private static void requireLeaseToken(TaskLease lease) {
        if (lease == null || lease.task() == null) throw staleLease();
        requireId(lease.id()); requireWorker(lease.workerId());
        if (lease.fence() <= 0) throw staleLease();
    }
    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private static ApplicationFault creationConflict() { return new ApplicationFault("TASK_CREATE_CONFLICT", 409, "任务提交发生冲突，请使用原幂等标识重试"); }
    private static ApplicationFault staleLease() { return new ApplicationFault("TASK_LEASE_LOST", 409, "任务租约已失效，请停止此轮处理"); }
    private static ApplicationFault notFound() { return new ApplicationFault("TASK_NOT_FOUND", 404, "任务不存在或无权访问"); }
    private record Creation(String scope, String digest) { }
}
