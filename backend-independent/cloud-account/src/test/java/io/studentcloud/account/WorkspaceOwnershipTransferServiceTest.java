package io.studentcloud.account;

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

class WorkspaceOwnershipTransferServiceTest {
    JdbcTemplate jdbc; DataSourceTransactionManager manager; WorkspaceOwnershipTransferService service;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
    Actor owner,recipient,outsider,global;
    @BeforeEach void setup(){
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:transfer_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-schema.sql"),new ClassPathResource("db/independent-workspace-ownership-transfer-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);manager=new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO sc_workspace(id,name,enabled,created_at) VALUES(1,'one',TRUE,0),(2,'two',TRUE,0)");
        owner=account(1,"TENANT_ADMIN",1L);recipient=account(2,"USER",1L);outsider=account(3,"USER",2L);global=account(4,"ADMIN",null);
        service=new WorkspaceOwnershipTransferService(jdbc,manager,clock,true);
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    Actor account(long id,String role,Long workspace){
        jdbc.update("INSERT INTO sc_account(id,account_id,username,password_hash,role,workspace_id,created_at) VALUES(?,?,?,'unused',?,?,0)",id,"SC"+id,"user"+id,role,workspace);
        return new JdbcAccountDirectory(jdbc).account(id);
    }
    WorkspaceOwnershipTransferService.Transfer pending(){return service.create(owner,2,0,clock.millis()+60000,"synthetic-key");}
    @Test void bothPartiesConfirmAndOldManagementAndSessionAreRevoked(){
        jdbc.update("INSERT INTO sc_session VALUES('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,1,?,0)",clock.millis()+100000);
        var transfer=pending();assertEquals("PENDING",transfer.state());
        assertThrows(ApplicationFault.class,()->service.decide(owner,transfer.id(),"accept"));
        var result=service.decide(recipient,transfer.id(),"accept");assertEquals("COMPLETED",result.state());
        assertEquals(result,service.decide(recipient,transfer.id(),"accept"));
        assertEquals("USER",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=1",String.class));
        assertEquals("TENANT_ADMIN",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=2",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_session WHERE account_id=1",Integer.class));
        assertThrows(ApplicationFault.class,()->service.create(owner,2,1,clock.millis()+60000,"next"));
    }
    @Test void sameKeySameRequestIsIdempotentAndChangedParametersConflict(){
        var transfer=pending();assertEquals(transfer,pending());
        assertThrows(ApplicationFault.class,()->service.create(owner,2,0,clock.millis()+70000,"synthetic-key"));
        assertThrows(ApplicationFault.class,()->service.create(owner,2,0,clock.millis()+60000,"another"));
    }
    @Test void disabledDefaultAndCrossWorkspaceGlobalAdminAndDisabledRecipientAreDenied(){
        assertThrows(ApplicationFault.class,()->new WorkspaceOwnershipTransferService(jdbc,manager,clock,false).create(owner,2,0,clock.millis()+60000,"key"));
        assertThrows(ApplicationFault.class,()->service.create(owner,3,0,clock.millis()+60000,"key"));
        assertThrows(ApplicationFault.class,()->service.create(global,2,0,clock.millis()+60000,"key"));
        var transfer=pending();jdbc.update("UPDATE sc_account SET enabled=FALSE WHERE id=2");
        assertThrows(ApplicationFault.class,()->service.decide(recipient,transfer.id(),"accept"));
        assertEquals("TENANT_ADMIN",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=1",String.class));
    }
    @Test void rejectCancelAndExpiryAreTerminalWithoutChangingOwner(){
        var transfer=pending();assertEquals("REJECTED",service.decide(recipient,transfer.id(),"reject").state());
        var next=service.create(owner,2,0,clock.millis()+60000,"next");
        assertEquals("CANCELLED",service.decide(owner,next.id(),"cancel").state());
        var last=service.create(owner,2,0,clock.millis()+60000,"last");
        var future=new WorkspaceOwnershipTransferService(jdbc,manager,Clock.offset(clock,Duration.ofMinutes(2)),true);
        assertEquals("EXPIRED",future.decide(recipient,last.id(),"accept").state());
        assertEquals("TENANT_ADMIN",jdbc.queryForObject("SELECT role FROM sc_account WHERE id=1",String.class));
    }
    @Test void revisionAndConcurrentConfirmationCannotCommitTwice()throws Exception{
        assertThrows(ApplicationFault.class,()->service.create(owner,2,1,clock.millis()+60000,"bad"));
        var transfer=pending();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<String> accept=()->{gate.await();return service.decide(recipient,transfer.id(),"accept").state();};
            var one=pool.submit(accept);var two=pool.submit(accept);gate.countDown();
            assertEquals("COMPLETED",one.get(10,TimeUnit.SECONDS));assertEquals("COMPLETED",two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT ownership_revision FROM sc_workspace WHERE id=1",Integer.class));
    }
}
