package io.studentcloud.drive.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class StorageOperationJournalTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager tx;
    StorageOperationJournal journal;
    Actor user=new Actor(3,"a3","member",null,Role.USER,1L);
    final Actor other=new Actor(4,"a4","other",null,Role.USER,1L);
    boolean granted=true;
    @BeforeEach void setup() {
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:journal_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-storage-operations-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);tx=new DataSourceTransactionManager(db);
        AccountDirectory accounts=new AccountDirectory(){
            public Actor account(long id){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return id==3?user:other;}
            public boolean workspaceEnabled(long id){return id==1;}
        };
        DriveOperations drives=(DriveOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DriveOperations.class},(p,m,a)->{
            if(m.getName().equals("listMounts"))return granted?List.of(new MountView(8,"drive","盘",1L,1,"LOCAL",true,1,Set.of("read","upload"))):List.of();
            throw new UnsupportedOperationException();
        });
        journal=new StorageOperationJournal(jdbc,tx,Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"),ZoneOffset.UTC),accounts,drives);
    }
    StorageOperationJournal.Receipt prepare(String key,String request){return journal.prepare(user,"drive","UPLOAD",key,request);}
    @Test void duplicateRequestHasOneReceiptAndDifferentParametersConflict() {
        var a=prepare("once","body-a");assertEquals(a.id(),prepare("once","body-a").id());
        assertEquals(409,assertThrows(ApplicationFault.class,()->prepare("once","body-b")).httpStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_operation",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_operation_event",Integer.class));
    }
    @Test void concurrentSameRequestCreatesOnlyOneOperation() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            Callable<String> work=()->{ready.countDown();go.await();return prepare("race","same").id();};
            var a=pool.submit(work);var b=pool.submit(work);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_operation",Integer.class));
    }
    @Test void committedTransitionsAndEventsSurviveCallerFailure() {
        var r=prepare("durable","data");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(tx).execute(status->{
            var lease=journal.begin(r.id());journal.committing(lease);throw new IllegalStateException("caller rollback");
        }));
        assertEquals("COMMITTING",journal.get(user,"drive",r.id()).state());
        assertEquals(List.of("PREPARED","RUNNING","COMMITTING"),jdbc.queryForList("SELECT state FROM sc_storage_operation_event WHERE operation_id=? ORDER BY sequence_no",String.class,r.id()));
    }
    @Test void wrapperNeverRetriesAnUnknownStorageOutcomeAndNeverStoresPrivateFailureText() {
        var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->journal.perform(user,"drive","UPLOAD","unknown","digest",()->{calls.incrementAndGet();throw new IllegalStateException("secret /private/path password=do-not-store");}));
        var page=journal.list(user,"drive",null,20);assertEquals("INDETERMINATE",page.items().getFirst().state());
        var again=journal.perform(user,"drive","UPLOAD","unknown","digest",()->{calls.incrementAndGet();return "bad replay";});
        assertTrue(again.replayed());assertNull(again.value());assertEquals(1,calls.get());
        assertEquals("OUTCOME_UNKNOWN",page.items().getFirst().errorCode());
        assertFalse(jdbc.queryForMap("SELECT * FROM sc_storage_operation").toString().contains("password"));
    }
    @Test void successIsRecordedOnlyAfterCallbackReturnsWithoutInventingAContentReference() {
        var result=journal.perform(user,"drive","MKDIR","good","request",()->{
            assertEquals("COMMITTING",jdbc.queryForObject("SELECT state FROM sc_storage_operation",String.class));
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return "actual-result";
        });
        assertEquals("actual-result",result.value());assertEquals("SUCCEEDED",result.receipt().state());
        assertNull(result.receipt().resultRef(),"A successful synchronous call is not proof of a content/version identity");
        assertEquals(List.of("PREPARED","RUNNING","COMMITTING","SUCCEEDED"),jdbc.queryForList("SELECT state FROM sc_storage_operation_event ORDER BY sequence_no",String.class));
    }
    @Test void staleFenceCannotCommitAndCancellationCannotClaimRunningWorkIsUndone() {
        var receipt=prepare("fence","data");var lease=journal.begin(receipt.id());
        assertEquals(409,assertThrows(ApplicationFault.class,()->journal.cancel(user,"drive",receipt.id())).httpStatus());
        jdbc.update("UPDATE sc_storage_operation SET fence=fence+1 WHERE id=?",receipt.id());
        assertEquals(409,assertThrows(ApplicationFault.class,()->journal.committing(lease)).httpStatus());
        assertEquals("RUNNING",journal.get(user,"drive",receipt.id()).state());
        var untouched=prepare("cancel","data");assertEquals("CANCELLED",journal.cancel(user,"drive",untouched.id()).state());
    }
    @Test void otherActorCrossWorkspaceAndRevokedGrantCannotReadReceipts() {
        var receipt=prepare("private","/private/path");
        assertEquals(404,assertThrows(ApplicationFault.class,()->journal.get(other,"drive",receipt.id())).httpStatus());
        assertTrue(journal.list(other,"drive",null,20).items().isEmpty());
        user=new Actor(3,"a3","member",null,Role.USER,2L);
        assertEquals(403,assertThrows(ApplicationFault.class,()->journal.get(user,"drive",receipt.id())).httpStatus());
        user=new Actor(3,"a3","member",null,Role.USER,1L);granted=false;
        assertEquals(403,assertThrows(ApplicationFault.class,()->journal.get(user,"drive",receipt.id())).httpStatus());
    }
    @Test void boundedCursorListingDoesNotDuplicateRowsWithIdenticalTimestamps() {
        for(int i=0;i<4;i++)prepare("key-"+i,"data");
        var first=journal.list(user,"drive",null,2);assertEquals(2,first.items().size());assertNotNull(first.nextCursor());
        var second=journal.list(user,"drive",first.nextCursor(),2);assertEquals(2,second.items().size());assertNull(second.nextCursor());
        var ids=new HashSet<String>();first.items().forEach(r->ids.add(r.id()));second.items().forEach(r->ids.add(r.id()));assertEquals(4,ids.size());
        assertEquals(400,assertThrows(ApplicationFault.class,()->journal.list(user,"drive",null,51)).httpStatus());
        assertEquals(400,assertThrows(ApplicationFault.class,()->journal.list(user,"drive","bad cursor",20)).httpStatus());
    }
}
