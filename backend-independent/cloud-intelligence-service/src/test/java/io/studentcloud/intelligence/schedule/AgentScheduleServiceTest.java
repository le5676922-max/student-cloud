package io.studentcloud.intelligence.schedule;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.agent.AgentPlanService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class AgentScheduleServiceTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private AgentScheduleService service;
    private Actor owner,other,admin,scopedAdmin;
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneOffset.UTC); // Monday, 09:00 Beijing
    private AtomicInteger previews;
    private AtomicReference<List<Object>> previewInput;
    private AgentPlanService.View plan;

    @BeforeEach void setUp(){
        var database=new DriverManagerDataSource("jdbc:h2:mem:agent_schedule_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(schema().getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(database);
        jdbc=new JdbcTemplate(database);tx=new DataSourceTransactionManager(database);owner=new Actor(10,"SC10","owner","owner@example.test",Role.USER,100L);other=new Actor(11,"SC11","other","other@example.test",Role.USER,100L);admin=new Actor(12,"SC12","admin","admin@example.test",Role.ADMIN,null);scopedAdmin=new Actor(13,"SC13","scoped","scoped@example.test",Role.ADMIN,100L);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return switch((int)id){case 10->owner;case 11->other;case 12->admin;case 13->scopedAdmin;default->throw new IllegalArgumentException("missing");};}public boolean workspaceEnabled(long id){return id==100;}};
        previews=new AtomicInteger();previewInput=new AtomicReference<>();plan=new AgentPlanService.View("plan-1","文件操作预览","PENDING",List.of(),List.of(),clock.millis()+60_000,clock.millis(),clock.millis(),null,"仅待确认");
        service=new AgentScheduleService(jdbc,tx,accounts,clock,(actor,key,path,type,course,tags)->{previews.incrementAndGet();previewInput.set(java.util.Arrays.asList(key,path,type,course,tags));return plan;});
    }

    @Test void createsListsAndTogglesBeijingWeeklyRulesWithOwnerIsolation(){
        var first=service.create(owner,input("周一整理",1,10,0));var second=service.create(other,input("我的规则",2,10,30));
        assertEquals("WAITING",first.previewState());assertEquals("ORGANIZE_FILES",first.type());
        assertEquals(Instant.parse("2026-10-26T02:00:00Z").toEpochMilli(),first.nextRunAt());
        assertEquals(1,service.list(owner).size());assertEquals(1,service.list(other).size());
        assertThrows(ApplicationFault.class,()->service.setEnabled(owner,second.id(),false));
        assertThrows(ApplicationFault.class,()->service.previewNow(owner,second.id()));
        var paused=service.setEnabled(owner,first.id(),false);assertFalse(paused.enabled());assertEquals("PAUSED",paused.previewState());assertNull(paused.nextRunAt());
        var resumed=service.setEnabled(owner,first.id(),true);assertTrue(resumed.enabled());assertEquals("WAITING",resumed.previewState());
        assertThrows(ApplicationFault.class,()->service.create(scopedAdmin,input("越权",1,10,0)));
        var globalRule=service.create(admin,input("全局个人盘规则",4,11,15));
        assertEquals(0,jdbc.queryForObject("SELECT workspace_id FROM sc_agent_schedule WHERE schedule_id=?",Long.class,globalRule.id()));
        assertEquals(1,service.list(admin).size());assertEquals("PREVIEW_CREATED",service.previewNow(admin,globalRule.id()).schedule().previewState());
    }

    @Test void atomicallyClaimsEachDueSlotOnceAndOnlyCreatesAnUnconfirmedPlan(){
        var schedule=service.create(owner,input("每周整理",1,10,0));jdbc.update("UPDATE sc_agent_schedule SET next_run_at=? WHERE schedule_id=?",clock.millis()-1,schedule.id());
        assertEquals(1,service.runDue());assertEquals(0,service.runDue());assertEquals(1,previews.get());
        var row=service.list(owner).getFirst();assertEquals("PREVIEW_CREATED",row.previewState());assertEquals("plan-1",jdbc.queryForObject("SELECT last_task_id FROM sc_agent_schedule WHERE schedule_id=?",String.class,schedule.id()));
        assertTrue(row.nextRunAt()>clock.millis());assertEquals("PENDING",plan.status());
    }

    @Test void previewFailuresArePersistedAndNeverRetriedAutomatically(){
        var failing=new AgentScheduleService(jdbc,tx,
                new AccountDirectory(){public Actor account(long id){return owner;}public boolean workspaceEnabled(long id){return true;}},clock,
                (actor,key,path,type,course,tags)->{previews.incrementAndGet();throw new ApplicationFault("CONTENT_UNAVAILABLE",503,"no preview");});
        var schedule=failing.create(owner,input("失败规则",1,10,0));jdbc.update("UPDATE sc_agent_schedule SET next_run_at=? WHERE schedule_id=?",clock.millis()-1,schedule.id());
        assertEquals(1,failing.runDue());assertEquals(0,failing.runDue());
        var row=failing.list(owner).stream().filter(item->item.id()==schedule.id()).findFirst().orElseThrow();
        assertEquals("FAILED",row.previewState());assertNotNull(row.lastError());assertNull(row.retryAt());assertEquals(0,row.retryCount());assertEquals(1,previews.get());
    }

    @Test void learningSchedulePassesCourseAndTagsOnlyToPreviewPort(){
        var input=new AgentScheduleService.CreateInput("IMPORT_LEARNING","local","/course",55L,"期末复习","课程归档",3,8,5);
        var created=service.create(owner,input);var result=service.previewNow(owner,created.id());
        assertEquals("PENDING",result.task().status());assertEquals(List.of("local","/course","IMPORT_LEARNING",55L,"期末复习"),previewInput.get());
        assertEquals("PREVIEW_CREATED",result.schedule().previewState());
    }

    @Test void enforcesTwentyPerOwnerAndStrictScheduleInputBounds(){
        for(int index=0;index<20;index++)service.create(owner,input("规则"+index,1,10,0));
        assertThrows(ApplicationFault.class,()->service.create(owner,input("超额",1,10,0)));
        assertThrows(ApplicationFault.class,()->service.create(owner,input("星期无效",8,10,0)));
        assertThrows(ApplicationFault.class,()->service.create(owner,new AgentScheduleService.CreateInput("IMPORT_LEARNING","local","/course",null,"", "课程归档",1,10,0)));
    }

    private AgentScheduleService.CreateInput input(String title,int day,int hour,int minute){return new AgentScheduleService.CreateInput("ORGANIZE_FILES","local","/course",null,"",title,day,hour,minute);}
    private static String schema(){return "CREATE TABLE sc_agent_schedule_guard(id INTEGER PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0);INSERT INTO sc_agent_schedule_guard(id,revision) VALUES(1,0);"
            +"CREATE TABLE sc_agent_schedule(schedule_id BIGINT AUTO_INCREMENT PRIMARY KEY,owner_account_id BIGINT NOT NULL,workspace_id BIGINT NOT NULL,title VARCHAR(100) NOT NULL,task_type VARCHAR(32) NOT NULL,storage_key VARCHAR(128) NOT NULL,directory_path VARCHAR(500) NOT NULL,course_id BIGINT,tags VARCHAR(200),day_of_week INTEGER NOT NULL,hour_of_day INTEGER NOT NULL,minute_of_hour INTEGER NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,next_run_at BIGINT,last_run_at BIGINT,last_error VARCHAR(500),preview_state VARCHAR(32) NOT NULL,retry_at BIGINT,retry_count INTEGER NOT NULL DEFAULT 0,last_task_id VARCHAR(36),claim_token VARCHAR(36),claim_started_at BIGINT,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL);";
    }
}
