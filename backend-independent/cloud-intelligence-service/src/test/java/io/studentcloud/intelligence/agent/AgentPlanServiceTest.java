package io.studentcloud.intelligence.agent;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.tasks.TaskService;
import io.studentcloud.tasks.TaskState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanServiceTest {
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private AtomicReference<Actor> current;
    private AgentPlanService service;
    private TaskService tasks;
    private Actor owner,admin,other;

    @BeforeEach void setUp(){
        var database=new DriverManagerDataSource("jdbc:h2:mem:agent_plan_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(schema().getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(database);
        jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database){
            @Override protected void doBegin(Object transaction,TransactionDefinition definition){
                var repeated=new org.springframework.transaction.support.DefaultTransactionDefinition(definition);
                repeated.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);super.doBegin(transaction,repeated);
            }
        };clock=new MutableClock(Instant.parse("2026-10-26T01:00:00Z"));
        owner=new Actor(10,"SC10","owner","owner@example.test",Role.USER,100L);admin=new Actor(12,"SC12","admin","admin@example.test",Role.ADMIN,null);other=new Actor(11,"SC11","other","other@example.test",Role.USER,100L);current=new AtomicReference<>(owner);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){if(id==10)return current.get();if(id==11)return other;if(id==12)return admin;throw new IllegalArgumentException("missing");}public boolean workspaceEnabled(long id){return id==100||id==200;}};
        AgentPlanService.PreviewPort preview=new AgentPlanService.PreviewPort(){
            public AgentContentClient.Preview preview(Actor actor,String key,List<AgentContentClient.Step> steps){return result();}
            public AgentContentClient.Preview previewBulk(Actor actor,String key,String path,String type,Long course,String tags){return result();}
            private AgentContentClient.Preview result(){return new AgentContentClient.Preview("opaque-preview",clock.millis()+600_000,List.of(new AgentContentClient.Step("MKDIR",null,"/demo")));}
        };
        tasks=new TaskService(jdbc,tx,clock);
        service=new AgentPlanService(jdbc,tx,clock,accounts,null,tasks,preview);
    }

    @Test void everyReadAndMutationIsScopedToTheCurrentWorkspaceIncludingRetries(){
        var first=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/one")));
        current.set(new Actor(owner.id(),owner.accountId(),owner.username(),owner.email(),Role.USER,200L));
        assertEquals("40400",assertThrows(ApplicationFault.class,()->service.get(owner,first.id())).code());
        assertEquals("40400",assertThrows(ApplicationFault.class,()->service.cancel(owner,first.id())).code());
        assertEquals("40400",assertThrows(ApplicationFault.class,()->service.retry(owner,first.id())).code());
        assertEquals("40400",assertThrows(ApplicationFault.class,()->service.confirm(owner,first.id(),true)).code());
        assertTrue(service.list(owner).isEmpty());
        var second=service.preview(current.get(),"local",List.of(new AgentContentClient.Step("MKDIR",null,"/two")));
        assertEquals(List.of(second.id()),service.list(current.get()).stream().map(AgentPlanService.View::id).toList());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE workspace_id=100",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE workspace_id=200",Integer.class));
    }

    @Test void ownerPendingBudgetIsSerializedAndExpiredPreviewsDoNotBlockNewOnes(){
        AgentPlanService.View first=null;
        for(int i=0;i<50;i++){var created=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/item"+i)));if(first==null)first=created;}
        assertEquals(50,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE account_id=10 AND state='PENDING'",Integer.class));
        assertEquals("AGENT_PLAN_LIMIT",assertThrows(ApplicationFault.class,()->service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/overflow")))).code());
        var otherPlan=service.preview(other,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/other")));
        assertEquals(otherPlan.id(),service.get(other,otherPlan.id()).id()); // another owner has an independent capacity
        clock.advance(11*60_000L);
        assertEquals("EXPIRED",service.get(owner,first.id()).status());
        var accepted=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/after-expiry")));
        assertEquals("PENDING",accepted.status());
        assertEquals(50,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE account_id=10 AND state='EXPIRED'",Integer.class));
    }

    @Test void cleanupPurgesOnlyOldUnconfirmedRowsAndRetainsConfirmedTaskHistory(){
        var pending=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/pending")));
        long old=clock.millis()-31L*24*60*60*1000;
        jdbc.update("UPDATE sc_agent_plan SET state='EXPIRED',updated_at=? WHERE plan_id=?",old,pending.id());
        jdbc.update("INSERT INTO sc_agent_plan(plan_id,account_id,workspace_id,storage_key,preview_token,steps_json,expires_at,job_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'CONFIRMED',?,?)",
                UUID.randomUUID().toString(),owner.id(),owner.workspaceId(),"local","confirmed-token","[]",clock.millis()-1000,"task-history",old,old);
        assertEquals(0,service.cleanupExpiredPreviews());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE plan_id=?",Integer.class,pending.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE job_id='task-history'",Integer.class));
    }

    @Test void nullWorkspaceAdminOnlySeesItsOwnNullWorkspacePlans(){
        var global=service.preview(admin,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/personal")));
        assertEquals(global.id(),service.get(admin,global.id()).id());
        assertEquals(List.of(global.id()),service.list(admin).stream().map(AgentPlanService.View::id).toList());
        assertThrows(ApplicationFault.class,()->service.get(owner,global.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE account_id=12 AND workspace_id IS NOT NULL",Integer.class));
    }

    @Test void confirmationCommitsTaskOutboxAndPlanTogetherAndRemainsIdempotentAfterCommit(){
        var pending=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/confirm")));
        var confirmed=service.confirm(owner,pending.id(),true);
        assertNotNull(confirmed.taskId());
        assertEquals("RUNNING",confirmed.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job WHERE job_id=? AND actor_id=?",Integer.class,confirmed.taskId(),owner.id()));
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT state FROM sc_agent_plan WHERE plan_id=?",String.class,pending.id()));
        assertEquals(confirmed.taskId(),jdbc.queryForObject("SELECT job_id FROM sc_agent_plan WHERE plan_id=?",String.class,pending.id()));
        assertEquals(1,tasks.pendingOutbox(20).size());
        var repeated=service.confirm(owner,pending.id(),true);
        assertEquals(confirmed.taskId(),repeated.taskId());
        assertEquals("RUNNING",repeated.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job",Integer.class));
        assertEquals(1,tasks.pendingOutbox(20).size());
    }

    @Test void planLinkFailureRollsBackTheNewJobAndOutboxAndLeavesPreviewPending(){
        var pending=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/confirm")));
        // Confirmation reaches this constraint only after inserting both the job and its outbox row.
        jdbc.execute("ALTER TABLE sc_agent_plan ADD CONSTRAINT reject_confirmation CHECK (state <> 'CONFIRMED')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.confirm(owner,pending.id(),true));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job_outbox",Integer.class));
        assertEquals("PENDING",service.get(owner,pending.id()).status());
        assertNull(service.get(owner,pending.id()).taskId());
        assertTrue(tasks.claim("worker",30).isEmpty());

        jdbc.execute("ALTER TABLE sc_agent_plan DROP CONSTRAINT reject_confirmation");
        var retried=service.confirm(owner,pending.id(),true);
        assertNotNull(retried.taskId());
        assertEquals("RUNNING",retried.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job",Integer.class));
        assertEquals(1,tasks.pendingOutbox(20).size());
    }

    @Test void failedExecutionsRetryThroughANewPreviewAndANewAtomicallyLinkedTask(){
        var original=service.preview(owner,"local",List.of(new AgentContentClient.Step("MKDIR",null,"/confirm")));
        var confirmed=service.confirm(owner,original.id(),true);
        var lease=tasks.claim("worker",30).orElseThrow();
        assertEquals(confirmed.taskId(),lease.id());
        tasks.fail(lease,"TOOL_EXECUTION_FAILED",false,Duration.ZERO);
        assertEquals("FAILED",service.get(owner,original.id()).status());

        var preview=service.retry(owner,original.id());
        assertNotEquals(original.id(),preview.id());
        assertEquals("PENDING",preview.status());
        assertNull(preview.taskId());
        var retried=service.confirm(owner,preview.id(),true);
        assertNotEquals(confirmed.taskId(),retried.taskId());
        assertEquals("RUNNING",retried.status());
        assertEquals(TaskState.FAILED,tasks.get(owner.id(),confirmed.taskId()).state());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sc_job",Integer.class));
        assertEquals(2,tasks.pendingOutbox(20).size());
    }

    private static String schema(){return "CREATE TABLE sc_agent_plan(plan_id VARCHAR(36) PRIMARY KEY,account_id BIGINT NOT NULL,workspace_id BIGINT,storage_key VARCHAR(128) NOT NULL,preview_token VARCHAR(256) NOT NULL,steps_json TEXT NOT NULL,expires_at BIGINT NOT NULL,job_id VARCHAR(36),state VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL);"
            +"CREATE INDEX sc_agent_plan_owner_idx ON sc_agent_plan(account_id,created_at);CREATE TABLE sc_agent_plan_guard(account_id BIGINT PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0);"
            +"CREATE TABLE sc_job(job_id VARCHAR(36) PRIMARY KEY,actor_id BIGINT NOT NULL,workspace_id BIGINT NOT NULL,task_type VARCHAR(32) NOT NULL,payload TEXT NOT NULL,payload_digest CHAR(64) NOT NULL,idempotency_hash CHAR(64) NOT NULL UNIQUE,state VARCHAR(16) NOT NULL,attempt_count INT NOT NULL,max_attempts INT NOT NULL,available_at BIGINT NOT NULL,worker_id VARCHAR(96),lease_until BIGINT,fence BIGINT NOT NULL,result_payload TEXT,failure_code VARCHAR(64),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,finished_at BIGINT);"
            +"CREATE TABLE sc_job_outbox(event_id VARCHAR(36) PRIMARY KEY,job_id VARCHAR(36) NOT NULL,event_type VARCHAR(32) NOT NULL,event_payload TEXT NOT NULL,created_at BIGINT NOT NULL,published_at BIGINT,CONSTRAINT uq_sc_job_created_event UNIQUE(job_id,event_type),CONSTRAINT fk_sc_job_outbox_job FOREIGN KEY(job_id) REFERENCES sc_job(job_id));";}

    private static final class MutableClock extends Clock {
        private Instant instant;MutableClock(Instant instant){this.instant=instant;}void advance(long millis){instant=instant.plusMillis(millis);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}
    }
}
