package io.studentcloud.intelligence.schedule;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.agent.AgentPlanService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns weekly preview triggers only. It never confirms or executes a file-operation plan. */
@Service
public final class AgentScheduleService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SUPPORTED_TYPES = Set.of("ORGANIZE_FILES", "RENAME_BY_DATE", "IMPORT_LEARNING");
    private static final String COLUMNS = "schedule_id,owner_account_id,workspace_id,title,task_type,storage_key,directory_path,course_id,tags,"
            + "day_of_week,hour_of_day,minute_of_hour,enabled,next_run_at,last_run_at,last_error,preview_state,retry_at,retry_count,last_task_id";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountDirectory accounts;
    private final Clock clock;
    private final PreviewPort previews;

    public AgentScheduleService(JdbcTemplate jdbc, PlatformTransactionManager manager, AccountDirectory accounts,
            Clock clock, PreviewPort previews) {
        this.jdbc=Objects.requireNonNull(jdbc);this.transactions=new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(20);this.accounts=Objects.requireNonNull(accounts);this.clock=Objects.requireNonNull(clock);
        this.previews=Objects.requireNonNull(previews);
    }

    /** Root configuration adapts AgentPlanService.previewBulk to this narrow testable port. */
    @FunctionalInterface public interface PreviewPort {
        AgentPlanService.View preview(Actor actor, String storageKey, String path, String type, Long courseId, String tags);
    }
    public record CreateInput(String type,String storageKey,String path,Long courseId,String tags,String title,
            Integer dayOfWeek,Integer hourOfDay,Integer minuteOfHour) { }
    public record ScheduleView(long id,String title,boolean enabled,int dayOfWeek,int hourOfDay,int minuteOfHour,
            String storageKey,String directoryPath,String type,Long courseId,String tags,Long lastRunAt,String lastError,Long nextRunAt,Long retryAt,
            int retryCount,String previewState) { }
    public record PreviewResponse(ScheduleView schedule,AgentPlanService.View task) { }

    public List<ScheduleView> list(Actor supplied) {
        Actor actor=actor(supplied);
        return jdbc.query("SELECT "+COLUMNS+" FROM sc_agent_schedule WHERE owner_account_id=? AND workspace_id=? ORDER BY created_at DESC,schedule_id DESC LIMIT 20",
                AgentScheduleService::map,actor.id(),workspaceId(actor));
    }

    public ScheduleView create(Actor supplied,CreateInput input) {
        Actor actor=actor(supplied);validate(input);
        return Objects.requireNonNull(transactions.execute(tx->{
            jdbc.queryForObject("SELECT id FROM sc_agent_schedule_guard WHERE id=1 FOR UPDATE",Integer.class);
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_schedule WHERE owner_account_id=?",Integer.class,actor.id());
            if(count>=20)throw fault("AGENT_SCHEDULE_LIMIT",409,"每个账号最多创建 20 条每周规则");
            long now=clock.millis();long next=nextOccurrence(input.dayOfWeek(),input.hourOfDay(),input.minuteOfHour(),now);
            jdbc.update("INSERT INTO sc_agent_schedule(owner_account_id,workspace_id,title,task_type,storage_key,directory_path,course_id,tags,day_of_week,hour_of_day,minute_of_hour,enabled,next_run_at,preview_state,retry_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,TRUE,?,'WAITING',0,?,?)",
                    actor.id(),workspaceId(actor),input.title().trim(),input.type(),input.storageKey(),input.path(),input.courseId(),input.tags(),input.dayOfWeek(),input.hourOfDay(),input.minuteOfHour(),next,now,now);
            Long id=jdbc.queryForObject("SELECT MAX(schedule_id) FROM sc_agent_schedule WHERE owner_account_id=?",Long.class,actor.id());
            return owned(actor,id,false);
        }));
    }

    public ScheduleView setEnabled(Actor supplied,long id,boolean enabled) {
        Actor actor=actor(supplied);
        return Objects.requireNonNull(transactions.execute(tx->{
            ScheduleView current=owned(actor,id,true);long now=clock.millis();
            if("GENERATING".equals(current.previewState()))throw fault("AGENT_SCHEDULE_BUSY",409,"预览正在生成，请稍后刷新");
            Long next=enabled?nextOccurrence(current.dayOfWeek(),current.hourOfDay(),current.minuteOfHour(),now):null;
            String state=enabled?"WAITING":"PAUSED";
            jdbc.update("UPDATE sc_agent_schedule SET enabled=?,next_run_at=?,preview_state=?,last_error=NULL,retry_at=NULL,retry_count=0,updated_at=? WHERE schedule_id=? AND owner_account_id=?",
                    enabled,next,state,now,id,actor.id());
            return owned(actor,id,false);
        }));
    }

    /** Explicit user request; one claimed invocation creates one reviewable plan and no task job. */
    public PreviewResponse previewNow(Actor supplied,long id) {
        Actor actor=actor(supplied);Claim claim=Objects.requireNonNull(transactions.execute(tx->{
            ScheduleView row=owned(actor,id,true);
            if("GENERATING".equals(row.previewState()))throw fault("AGENT_SCHEDULE_BUSY",409,"预览正在生成，请稍后刷新");
            long now=clock.millis();String token=UUID.randomUUID().toString();
            Long next=row.nextRunAt();if(next==null||next<=now)next=nextOccurrence(row.dayOfWeek(),row.hourOfDay(),row.minuteOfHour(),now);
            jdbc.update("UPDATE sc_agent_schedule SET next_run_at=?,last_run_at=?,last_error=NULL,preview_state='GENERATING',claim_token=?,claim_started_at=?,updated_at=? WHERE schedule_id=? AND owner_account_id=?",
                    next,now,token,now,now,id,actor.id());
            return new Claim(id,token,row.type(),row.storageKey(),row.directoryPath(),row.courseId(),row.tags());
        }));
        return executePreview(actor,claim);
    }

    /** Called by root-owned @Scheduled wiring. A conditional SQL claim advances the due slot first. */
    public int runDue() {
        long now=clock.millis();List<Claim> claimed=Objects.requireNonNull(transactions.execute(tx->{
            List<Long> ids=jdbc.queryForList("SELECT schedule_id FROM sc_agent_schedule WHERE enabled=TRUE AND next_run_at<=? AND preview_state<>'GENERATING' ORDER BY next_run_at,schedule_id LIMIT 2",Long.class,now);
            List<Claim> result=new ArrayList<>();
            for(long id:ids){
                ScheduleView row=jdbc.query("SELECT "+COLUMNS+" FROM sc_agent_schedule WHERE schedule_id=? FOR UPDATE",AgentScheduleService::map,id).stream().findFirst().orElse(null);
                if(row==null||!row.enabled()||row.nextRunAt()==null||row.nextRunAt()>now)continue;
                String token=UUID.randomUUID().toString();long next=nextOccurrence(row.dayOfWeek(),row.hourOfDay(),row.minuteOfHour(),now);
                int changed=jdbc.update("UPDATE sc_agent_schedule SET next_run_at=?,last_run_at=?,last_error=NULL,preview_state='GENERATING',claim_token=?,claim_started_at=?,updated_at=? WHERE schedule_id=? AND enabled=TRUE AND next_run_at<=? AND preview_state<>'GENERATING'",
                        next,now,token,now,now,id,now);
                if(changed==1)result.add(new Claim(id,token,row.type(),row.storageKey(),row.directoryPath(),row.courseId(),row.tags()));
            }
            return result;
        }));
        for(Claim claim:claimed){
            try {Actor current=actor(accounts.account(ownerOf(claim.id())));if(workspaceId(current)!=workspaceOf(claim.id()))throw forbidden();executePreview(current,claim);}
            catch(RuntimeException failed){recordFailure(claim,safeError(failed));}
        }
        return claimed.size();
    }

    /** Converts orphaned claims into a visible uncertainty state; it never retries them. */
    public int expireStaleClaims(long olderThanMillis) {
        if(olderThanMillis<30_000L||olderThanMillis>86_400_000L)throw invalid("过期检查窗口无效");long cutoff=clock.millis()-olderThanMillis;
        return jdbc.update("UPDATE sc_agent_schedule SET preview_state='UNCERTAIN',last_error='生成结果未确认；请检查任务列表后手动操作',claim_token=NULL,claim_started_at=NULL,updated_at=? WHERE preview_state='GENERATING' AND claim_started_at<=?",clock.millis(),cutoff);
    }

    private PreviewResponse executePreview(Actor actor,Claim claim) {
        try {
            AgentPlanService.View task=previews.preview(actor,claim.storageKey(),claim.path(),claim.taskType(),claim.courseId(),claim.tags());
            if(task==null||task.id()==null||task.id().isBlank()||!"PENDING".equals(task.status())||task.taskId()!=null||task.expiresAt()<=clock.millis())throw fault("AGENT_PREVIEW_UNAVAILABLE",503,"预览服务未返回有效的待确认方案");
            int saved=jdbc.update("UPDATE sc_agent_schedule SET preview_state='PREVIEW_CREATED',last_task_id=?,last_error=NULL,claim_token=NULL,claim_started_at=NULL,updated_at=? WHERE schedule_id=? AND owner_account_id=? AND claim_token=?",
                    task.id(),clock.millis(),claim.id(),actor.id(),claim.token());
            if(saved!=1)throw fault("AGENT_SCHEDULE_CLAIM_LOST",409,"预览已生成，但规则状态发生变化；请检查任务列表与规则状态");
            return new PreviewResponse(owned(actor,claim.id(),false),task);
        } catch(RuntimeException failure) {
            String error=safeError(failure);recordFailure(claim,error);throw failure;
        }
    }

    private void recordFailure(Claim claim,String error) {
        jdbc.update("UPDATE sc_agent_schedule SET preview_state='FAILED',last_error=?,retry_at=NULL,retry_count=0,claim_token=NULL,claim_started_at=NULL,updated_at=? WHERE schedule_id=? AND claim_token=?",
                error,clock.millis(),claim.id(),claim.token());
    }

    private long ownerOf(long id){return jdbc.queryForObject("SELECT owner_account_id FROM sc_agent_schedule WHERE schedule_id=?",Long.class,id);}
    private long workspaceOf(long id){return jdbc.queryForObject("SELECT workspace_id FROM sc_agent_schedule WHERE schedule_id=?",Long.class,id);}
    private ScheduleView owned(Actor actor,long id,boolean lock){
        if(id<1)throw notFound();return jdbc.query("SELECT "+COLUMNS+" FROM sc_agent_schedule WHERE schedule_id=? AND owner_account_id=? AND workspace_id=?"+(lock?" FOR UPDATE":""),AgentScheduleService::map,id,actor.id(),workspaceId(actor))
                .stream().findFirst().orElseThrow(AgentScheduleService::notFound);
    }
    private Actor actor(Actor supplied){Actor current=accounts.current(supplied);if(current.role()==Role.ADMIN&&current.workspaceId()==null)return current;if((current.role()!=Role.USER&&current.role()!=Role.TENANT_ADMIN)||current.workspaceId()==null||current.workspaceId()<1||!accounts.workspaceEnabled(current.workspaceId()))throw forbidden();return current;}
    private static long workspaceId(Actor actor){return actor.workspaceId()==null?0L:actor.workspaceId();}

    private static void validate(CreateInput input){
        if(input==null||input.type()==null||!SUPPORTED_TYPES.contains(input.type()))throw fault("AGENT_SCHEDULE_TYPE_UNAVAILABLE",400,"每周规则类型暂不支持");
        if(input.storageKey()==null||!input.storageKey().matches("[A-Za-z0-9._-]{1,100}")||input.path()==null||!validPath(input.path()))throw invalid("请选择有效的本地云盘和目录");
        if(input.title()==null||input.title().isBlank()||input.title().trim().length()>100||hasControl(input.title()))throw invalid("规则名称须为 1 至 100 字符");
        if(input.dayOfWeek()==null||input.dayOfWeek()<1||input.dayOfWeek()>7||input.hourOfDay()==null||input.hourOfDay()<0||input.hourOfDay()>23||input.minuteOfHour()==null||input.minuteOfHour()<0||input.minuteOfHour()>59)throw invalid("每周时间设置无效");
        if(input.tags()!=null&&(input.tags().length()>200||hasControl(input.tags())))throw invalid("标签不能超过 200 字符");
        if("IMPORT_LEARNING".equals(input.type())&&(input.courseId()==null||input.courseId()<1))throw invalid("课程归档规则须选择本人课程");
        if(!"IMPORT_LEARNING".equals(input.type())&&input.courseId()!=null)throw invalid("此任务类型不能关联课程");
    }
    private static boolean validPath(String value){if(value.length()>500||!value.startsWith("/")||!value.equals(value.trim())||value.contains("//")||value.matches(".*[\\\\%:?#].*")||hasControl(value))return false;for(String part:value.split("/",-1))if(".".equals(part)||"..".equals(part))return false;return true;}
    private static boolean hasControl(String value){return value.chars().anyMatch(c->c<32||c==127);}
    private static long nextOccurrence(int day,int hour,int minute,long now){
        var current=Instant.ofEpochMilli(now).atZone(ZONE);LocalDateTime candidate=current.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day))).atTime(hour,minute);
        if(!candidate.atZone(ZONE).toInstant().isAfter(Instant.ofEpochMilli(now)))candidate=candidate.plusWeeks(1);
        return candidate.atZone(ZONE).toInstant().toEpochMilli();
    }
    private static ScheduleView map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new ScheduleView(r.getLong("schedule_id"),r.getString("title"),r.getBoolean("enabled"),r.getInt("day_of_week"),r.getInt("hour_of_day"),r.getInt("minute_of_hour"),r.getString("storage_key"),r.getString("directory_path"),r.getString("task_type"),nullableLong(r,"course_id"),r.getString("tags"),nullableLong(r,"last_run_at"),r.getString("last_error"),nullableLong(r,"next_run_at"),nullableLong(r,"retry_at"),r.getInt("retry_count"),r.getString("preview_state"));}
    private static Long nullableLong(java.sql.ResultSet r,String column)throws java.sql.SQLException{long value=r.getLong(column);return r.wasNull()?null:value;}
    private static String safeError(Throwable failure){if(failure instanceof ApplicationFault fault){return switch(fault.code()){case "AGENT_CONTENT_REJECTED"->"当前文件权限或目录状态不允许预览，请检查后手动重试";case "CONTENT_UNAVAILABLE"->"文件预览服务暂不可用；未自动重试";default->"生成预览失败；未自动重试";};}return "生成预览失败；未自动重试";}
    private record Claim(long id,String token,String taskType,String storageKey,String path,Long courseId,String tags){}
    private static ApplicationFault invalid(String message){return fault("AGENT_SCHEDULE_INVALID",400,message);}
    private static ApplicationFault forbidden(){return fault("AGENT_SCHEDULE_FORBIDDEN",403,"无权访问此每周规则");}
    private static ApplicationFault notFound(){return fault("AGENT_SCHEDULE_NOT_FOUND",404,"规则不存在或无权访问");}
    private static ApplicationFault fault(String code,int status,String message){return new ApplicationFault(code,status,message);}
}
