package io.studentcloud.course;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class CourseOwnershipTransferServiceTest {
    JdbcTemplate jdbc; DataSourceTransactionManager manager; CourseOwnershipTransferService service;SharedCourseService courses;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
    Actor owner,recipient,outsider,user;long course;
    @BeforeEach void setup(){
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:course_transfer_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-courses-schema.sql"),new ClassPathResource("db/independent-course-materials-schema.sql"),new ClassPathResource("db/independent-course-ownership-transfer-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);manager=new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'one',TRUE,0),(2,'two',TRUE,0)");
        owner=account(1,"TENANT_ADMIN",1L);recipient=account(2,"TENANT_ADMIN",1L);outsider=account(3,"TENANT_ADMIN",2L);user=account(4,"USER",1L);
        var directory=new JdbcAccountDirectory(jdbc);courses=new SharedCourseService(jdbc,manager,clock,directory);
        course=courses.create(owner,"Synthetic shared course","",1024*1024,true).id();
        String code=courses.createInvitation(owner,course,null,null).code();courses.join(recipient,code);courses.join(outsider,code);courses.join(user,code);
        service=new CourseOwnershipTransferService(jdbc,manager,clock,directory,true);
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    Actor account(long id,String role,Long workspace){
        jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,created_at) VALUES(?,?,?,'unused',?,?,0)",id,"SC"+id,"user"+id,role,workspace);
        return new JdbcAccountDirectory(jdbc).account(id);
    }
    CourseOwnershipTransferService.Transfer pending(){return service.create(owner,course,2,0,clock.millis()+60000,"synthetic-key");}
    @Test void onlyRecipientMayAcceptAndOldOwnerImmediatelyLosesAllManagement(){
        var transfer=pending();assertThrows(ApplicationFault.class,()->service.decide(owner,course,transfer.id(),"accept"));
        var result=service.decide(recipient,course,transfer.id(),"accept");assertEquals("COMPLETED",result.state());
        assertEquals(result,service.decide(recipient,course,transfer.id(),"accept"));
        assertTrue(courses.get(recipient,course).creator());
        assertThrows(ApplicationFault.class,()->courses.update(owner,course,"No","",1024*1024,true));
        assertThrows(ApplicationFault.class,()->courses.createInvitation(owner,course,null,null));
        assertThrows(ApplicationFault.class,()->courses.requireUpload(owner,course));
        assertEquals(1,jdbc.queryForObject("SELECT ownership_revision FROM sc_shared_course WHERE id=?",Integer.class,course));
        assertEquals("TENANT_ADMIN",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=1",String.class));
    }
    @Test void recipientMustBeSameWorkspaceCurrentAdministratorAndActiveCourseMember(){
        assertThrows(ApplicationFault.class,()->service.create(owner,course,3,0,clock.millis()+60000,"cross"));
        assertThrows(ApplicationFault.class,()->service.create(owner,course,4,0,clock.millis()+60000,"user"));
        courses.leave(recipient,course);assertThrows(ApplicationFault.class,this::pending);
    }
    @Test void requestIsIdempotentButSecondPendingOrChangedKeyParametersConflict(){
        var transfer=pending();assertEquals(transfer,pending());
        assertThrows(ApplicationFault.class,()->service.create(owner,course,2,0,clock.millis()+70000,"synthetic-key"));
        assertThrows(ApplicationFault.class,()->service.create(owner,course,2,0,clock.millis()+60000,"other"));
        assertThrows(ApplicationFault.class,()->service.create(owner,course,2,1,clock.millis()+60000,"revision"));
    }
    @Test void disabledExpiryRejectionAndCancellationNeverChangeOwner(){
        assertThrows(ApplicationFault.class,()->new CourseOwnershipTransferService(jdbc,manager,clock,new JdbcAccountDirectory(jdbc),false).create(owner,course,2,0,clock.millis()+60000,"off"));
        var transfer=pending();assertEquals("REJECTED",service.decide(recipient,course,transfer.id(),"reject").state());
        var next=service.create(owner,course,2,0,clock.millis()+60000,"next");assertEquals("CANCELLED",service.decide(owner,course,next.id(),"cancel").state());
        var last=service.create(owner,course,2,0,clock.millis()+60000,"last");
        jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=2");
        assertThrows(ApplicationFault.class,()->service.decide(recipient,course,last.id(),"accept"));
        jdbc.update("UPDATE sc_account SET enabled=TRUE WHERE id=2");
        var future=new CourseOwnershipTransferService(jdbc,manager,Clock.offset(clock,Duration.ofMinutes(2)),new JdbcAccountDirectory(jdbc),true);
        assertEquals("EXPIRED",future.decide(recipient,course,last.id(),"accept").state());assertTrue(courses.get(owner,course).creator());
    }
    @Test void concurrentAcceptanceCommitsOnlyOneOwnershipGeneration()throws Exception{
        var transfer=pending();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<String> accept=()->{gate.await();return service.decide(recipient,course,transfer.id(),"accept").state();};
            var one=pool.submit(accept);var two=pool.submit(accept);gate.countDown();
            assertEquals("COMPLETED",one.get(10,TimeUnit.SECONDS));assertEquals("COMPLETED",two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT ownership_revision FROM sc_shared_course WHERE id=?",Integer.class,course));
    }
    @Test void formerWorkspaceAdministratorCanHandOverOwnOrphanedCourseButCannotManageIt(){
        jdbc.update("UPDATE sc_account SET role='USER' WHERE id=1");
        var former=new JdbcAccountDirectory(jdbc).account(1);
        assertTrue(courses.list(former).stream().anyMatch(row->row.id()==course&&row.creator()));
        assertTrue(service.snapshot(former,course).canCreate());
        assertThrows(ApplicationFault.class,()->courses.createInvitation(former,course,null,null));
        assertEquals(403,assertThrows(ApplicationFault.class,()->courses.requireUpload(former,course)).httpStatus());
        var materials=new CourseMaterialService(jdbc,manager,clock,courses,null);
        assertEquals(403,assertThrows(ApplicationFault.class,()->materials.incomplete(former,course)).httpStatus());
        var transfer=service.create(former,course,2,0,clock.millis()+60000,"after-workspace-handover");
        assertEquals("COMPLETED",service.decide(recipient,course,transfer.id(),"accept").state());
    }
    @Test void transferCannotBypassRecipientAggregateCourseBudget(){
        jdbc.update("UPDATE sc_shared_course SET used_bytes=? WHERE id=?",1024L*1024*1024,course);
        courses.create(recipient,"Other course","",1024*1024,true);
        jdbc.update("UPDATE sc_shared_course SET used_bytes=? WHERE owner_id=2",1536L*1024*1024);
        assertThrows(ApplicationFault.class,this::pending);
    }
}
