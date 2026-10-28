package io.studentcloud.intelligence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.tasks.CreateTask;
import io.studentcloud.tasks.TaskRecord;
import io.studentcloud.tasks.TaskService;
import io.studentcloud.tasks.TaskState;
import io.studentcloud.tasks.TaskType;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** User confirmation is required between content-owned preview and a durable execution task. */
public final class AgentPlanService {
    private static final int MAX_PENDING_PER_OWNER = 50;
    private static final long RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final AgentContentClient content;
    private final PreviewPort previews;
    private final TaskService tasks;
    private final ObjectMapper json = new ObjectMapper();

    public AgentPlanService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            AccountDirectory accounts, AgentContentClient content, TaskService tasks) {
        this(jdbc,manager,clock,accounts,content,tasks,new PreviewPort(){
            public AgentContentClient.Preview preview(Actor actor,String storageKey,List<AgentContentClient.Step> steps){return content.preview(actor,storageKey,steps);}
            public AgentContentClient.Preview previewBulk(Actor actor,String storageKey,String path,String type,Long courseId,String tags){return content.previewBulk(actor,storageKey,path,type,courseId,tags);}
        });
    }

    /** Narrow port keeps plan persistence and concurrency limits testable without an HTTP service. */
    public AgentPlanService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            AccountDirectory accounts, AgentContentClient content, TaskService tasks, PreviewPort previews) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.clock=clock;
        this.accounts=accounts; this.content=content; this.tasks=tasks; this.previews=Objects.requireNonNull(previews);
    }

    public interface PreviewPort {
        AgentContentClient.Preview preview(Actor actor,String storageKey,List<AgentContentClient.Step> steps);
        AgentContentClient.Preview previewBulk(Actor actor,String storageKey,String path,String type,Long courseId,String tags);
    }

    public View preview(Actor actor, String storageKey, List<AgentContentClient.Step> steps) {
        Actor current = accounts.current(actor);
        if (storageKey == null || !storageKey.matches("[A-Za-z0-9._-]{1,128}") || steps == null || steps.isEmpty() || steps.size() > 12)
            throw invalid();
        for (var step : steps) if (step == null || !List.of("MKDIR","RENAME","COPY","MOVE","INDEX_MATERIAL").contains(step.op())
                || !"INDEX_MATERIAL".equals(step.op()) && step.targetPath() == null || step.targetPath()!=null && step.targetPath().length() > 1024
                || step.sourcePath() != null && step.sourcePath().length() > 1024) throw invalid();
        AgentContentClient.Preview reviewed = previews.preview(current, storageKey, steps);
        return save(current, storageKey, reviewed);
    }

    public View previewBulk(Actor actor, String storageKey, String path, String type) {
        return previewBulk(actor,storageKey,path,type,null,null);
    }
    public View previewBulk(Actor actor, String storageKey, String path, String type,Long courseId,String tags) {
        Actor current = accounts.current(actor);
        if (storageKey == null || !storageKey.matches("[A-Za-z0-9._-]{1,128}") || path == null || path.length() > 1024
                || !List.of("ORGANIZE_FILES", "RENAME_BY_DATE","IMPORT_LEARNING").contains(type)
                || tags!=null && tags.length()>200 || "IMPORT_LEARNING".equals(type) && (courseId==null || courseId<1)) throw invalid();
        return save(current, storageKey, previews.previewBulk(current, storageKey, path, type,courseId,tags));
    }

    private View save(Actor current, String storageKey, AgentContentClient.Preview reviewed) {
        if (reviewed.previewToken() == null || reviewed.previewToken().isBlank() || reviewed.previewToken().length() > 256
                || reviewed.expiresAt() <= clock.millis() || reviewed.expiresAt() > clock.millis() + 11 * 60_000L
                || reviewed.steps() == null || reviewed.steps().isEmpty()) throw unavailable();
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        try {
            tx.executeWithoutResult(status -> {
                lockOwner(current.id());
                expireForOwner(current.id(), now);
                int pending=jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE account_id=? AND state='PENDING' AND expires_at>?",Integer.class,current.id(),now);
                if(pending>=MAX_PENDING_PER_OWNER)throw new ApplicationFault("AGENT_PLAN_LIMIT",429,"待确认文件预览过多，请先处理或等待过期");
                try {
                    jdbc.update("INSERT INTO sc_agent_plan(plan_id,account_id,workspace_id,storage_key,preview_token,steps_json,expires_at,state,created_at,updated_at) VALUES(?,?,?,?,?,? ,?,'PENDING',?,?)",
                            id, current.id(), current.workspaceId(), storageKey, reviewed.previewToken(), json.writeValueAsString(reviewed.steps()), reviewed.expiresAt(), now, now);
                } catch(JsonProcessingException impossible){throw new IllegalStateException(impossible);}
            });
        } catch (DuplicateKeyException raced) { throw new ApplicationFault("AGENT_PLAN_CONFLICT",409,"预览保存冲突，请重新生成"); }
        return get(current, id);
    }

    public View confirm(Actor actor, String planId, boolean confirmed) {
        if (!confirmed) throw new ApplicationFault("AGENT_CONFIRM_REQUIRED", 400, "请明确确认文件操作");
        Actor current = accounts.current(actor);
        String savedId = Objects.requireNonNull(tx.execute(status -> {
            Plan plan = owned(current, planId, true);
            if (plan.expiresAt <= clock.millis()) throw new ApplicationFault("AGENT_PREVIEW_EXPIRED", 409, "预览已过期，请重新检查操作");
            if ("CANCELLED".equals(plan.state)) throw new ApplicationFault("AGENT_CANCELLED", 409, "任务已取消");
            if (!"PENDING".equals(plan.state) && !"CONFIRMED".equals(plan.state)) throw invalid();
            String payload;
            try { payload = json.writeValueAsString(java.util.Map.of("planId", plan.id, "previewToken", plan.previewToken)); }
            catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
            TaskRecord task = tasks.createInCurrentTransaction(new CreateTask(current.id(), current.workspaceId() == null ? 0 : current.workspaceId(),
                    TaskType.FILE_ORGANIZE, payload, plan.id));
            int linked = jdbc.update("UPDATE sc_agent_plan SET state='CONFIRMED',job_id=?,updated_at=? WHERE plan_id=?", task.id(), clock.millis(), plan.id);
            if (linked != 1) throw new ApplicationFault("AGENT_PLAN_CONFLICT", 409, "预览状态已变化，请重试");
            // The plan, job, and creation event commit together. Return the public view only
            // after commit, so no queued task can become visible before its plan link exists.
            return plan.id;
        }));
        return get(current, savedId);
    }

    public View cancel(Actor actor, String planId) {
        Actor current = accounts.current(actor);
        return Objects.requireNonNull(tx.execute(status -> {
            Plan plan = owned(current, planId, true);
            if ("PENDING".equals(plan.state))
                jdbc.update("UPDATE sc_agent_plan SET state='CANCELLED',updated_at=? WHERE plan_id=?", clock.millis(), plan.id);
            else if (plan.jobId != null) tasks.cancel(current.id(), plan.jobId);
            return view(owned(current, planId, false));
        }));
    }

    public View get(Actor actor, String planId) {
        Actor current = accounts.current(actor);
        return view(owned(current, planId, false));
    }

    public View retry(Actor actor, String planId) {
        View current = get(actor, planId);
        if (!List.of("FAILED", "PARTIAL", "INTERRUPTED").contains(current.status()))
            throw new ApplicationFault("AGENT_RETRY_NOT_ALLOWED", 409, "当前任务无需重试");
        Actor currentActor=accounts.current(actor);
        Plan original = owned(currentActor, planId, false);
        return preview(actor, original.storageKey, rawSteps(original.stepsJson));
    }

    private View view(Plan plan) {
        List<AgentContentClient.Step> steps = rawSteps(plan.stepsJson);
        String state = "PENDING".equals(plan.state) && plan.expiresAt<=clock.millis() ? "EXPIRED" : plan.state;
        List<AgentContentClient.StepResult> results = List.of();
        if (plan.jobId != null) {
            TaskRecord task = tasks.get(plan.actorId, plan.jobId);
            state = switch (task.state()) {
                case QUEUED, RUNNING -> "RUNNING";
                case CANCELLED -> "CANCELLED";
                case FAILED -> "FAILED";
                case SUCCEEDED -> "COMPLETED";
            };
            if (task.result() != null) try {
                AgentContentClient.Execution execution = json.readValue(task.result(), AgentContentClient.Execution.class);
                state = switch (execution.status()) { case "COMPLETED" -> "SUCCEEDED"; case "INDETERMINATE" -> "INTERRUPTED"; default -> execution.status(); };
                results = execution.results() == null ? List.of() : execution.results();
            } catch (JsonProcessingException malformed) { throw new IllegalStateException("Stored result is invalid", malformed); }
        }
        List<ViewStep> shown = steps.stream().map(step -> new ViewStep(switch (step.op()) {
            case "MOVE" -> "MOVE_FILE"; case "RENAME" -> "RENAME_FILE"; case "COPY" -> "COPY_FILE"; case "MKDIR" -> "MAKE_DIRECTORY";
            case "INDEX_MATERIAL" -> "INDEX_MATERIAL";
            default -> "UNKNOWN";
        }, plan.storageKey, step.sourcePath(), step.targetPath(),step.courseId(),step.tags())).toList();
        return new View(plan.id, "文件操作预览", state, shown, results, plan.expiresAt, plan.createdAt, plan.updatedAt, plan.jobId,
                "仅执行预览的步骤；无删除、无覆盖。完成前每一步由文件服务重新核验权限与文件状态。");
    }

    public List<View> list(Actor actor) {
        Actor current = accounts.current(actor);
        return jdbc.queryForList("SELECT plan_id FROM sc_agent_plan WHERE account_id=? AND ((workspace_id=?) OR (workspace_id IS NULL AND ? IS NULL)) ORDER BY created_at DESC,plan_id DESC LIMIT 30",
                String.class, current.id(), current.workspaceId(), current.workspaceId()).stream().map(id -> view(owned(current,id,false))).toList();
    }

    /** Marks expired previews and removes only old, unconfirmed rows; confirmed task history is permanent. */
    public int cleanupExpiredPreviews() {
        long now=clock.millis();
        return Objects.requireNonNull(tx.execute(status->{
            int expired=jdbc.update("UPDATE sc_agent_plan SET state='EXPIRED',updated_at=? WHERE state='PENDING' AND expires_at<=?",now,now);
            jdbc.update("DELETE FROM sc_agent_plan WHERE job_id IS NULL AND state IN ('EXPIRED','CANCELLED') AND updated_at<=?",now-RETENTION_MILLIS);
            return expired;
        }));
    }

    private void expireForOwner(long accountId,long now){
        jdbc.update("UPDATE sc_agent_plan SET state='EXPIRED',updated_at=? WHERE account_id=? AND state='PENDING' AND expires_at<=?",now,accountId,now);
        jdbc.update("DELETE FROM sc_agent_plan WHERE account_id=? AND job_id IS NULL AND state IN ('EXPIRED','CANCELLED') AND updated_at<=?",accountId,now-RETENTION_MILLIS);
    }
    private void lockOwner(long accountId){
        jdbc.update("INSERT INTO sc_agent_plan_guard(account_id,revision) VALUES(?,0) ON DUPLICATE KEY UPDATE revision=revision",accountId);
        jdbc.queryForObject("SELECT account_id FROM sc_agent_plan_guard WHERE account_id=? FOR UPDATE",Long.class,accountId);
    }
    public AgentContentClient.DuplicateReport duplicates(Actor actor,String storageKey,String path){
        Actor current=accounts.current(actor);
        if(storageKey==null || !storageKey.matches("[a-z0-9][a-z0-9_-]{2,63}") || path==null || path.length()>1024)throw invalid();
        return content.duplicates(current,storageKey,path);
    }

    private List<AgentContentClient.Step> rawSteps(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored plan is invalid", invalid); }
    }

    private Plan owned(Actor actor, String id, boolean lock) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw forbidden();
        return jdbc.query("SELECT * FROM sc_agent_plan WHERE plan_id=? AND account_id=? AND ((workspace_id=?) OR (workspace_id IS NULL AND ? IS NULL))" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Plan(r.getString("plan_id"), r.getLong("account_id"), r.getObject("workspace_id", Long.class),
                        r.getString("storage_key"), r.getString("preview_token"), r.getString("steps_json"), r.getLong("expires_at"),
                        r.getString("state"), r.getString("job_id"), r.getLong("created_at"), r.getLong("updated_at")), id, actor.id(), actor.workspaceId(), actor.workspaceId())
                .stream().findFirst().orElseThrow(AgentPlanService::forbidden);
    }
    public record View(String id, String title, String status, List<ViewStep> steps,
                       List<AgentContentClient.StepResult> results, long expiresAt,
                       long createdAt, long updatedAt, String taskId, String notice) { }
    public record ViewStep(String action, String storageKey, String sourcePath, String targetPath,Long courseId,String tags) { }
    private record Plan(String id, long actorId, Long workspaceId, String storageKey, String previewToken,
                        String stepsJson, long expiresAt, String state, String jobId, long createdAt, long updatedAt) { }
    private static ApplicationFault invalid() { return new ApplicationFault("41000", 400, "文件操作参数无效"); }
    private static ApplicationFault forbidden() { return new ApplicationFault("40400", 404, "任务不存在或无权访问"); }
    private static ApplicationFault unavailable() { return new ApplicationFault("CONTENT_UNAVAILABLE", 503, "文件预览服务暂不可用"); }
}
