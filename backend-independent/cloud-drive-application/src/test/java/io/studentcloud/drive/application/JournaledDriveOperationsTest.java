package io.studentcloud.drive.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class JournaledDriveOperationsTest {
    @TempDir Path temp;JdbcTemplate jdbc;MountService local;StorageOperationJournal journal;DriveOperations wrapped;
    final Actor admin=new Actor(1,"a1","admin",null,Role.ADMIN,null),member=new Actor(3,"a3","member",null,Role.USER,1L);
    @BeforeEach void setup()throws Exception {
        Path managed=Files.createDirectory(temp.resolve("managed")),root=Files.createDirectory(managed.resolve("disk"));Files.createDirectories(root.resolve("members/u3"));
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:journal_local_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-storage-operations-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);var clock=Clock.systemUTC();
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:member;}public boolean workspaceEnabled(long id){return id==1;}};
        local=new MountService(jdbc,tx,clock,managed,accounts);var mount=local.createLocal(admin,"drive","drive",root,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,mkdir,upload,copy,move,rename,delete',TRUE,1,1)",mount.id());
        journal=new StorageOperationJournal(jdbc,tx,clock,accounts,local);
        wrapped=new JournaledDriveOperations(local,journal,true);
    }
    @Test void synchronousLocalWritesCreateReceiptsAfterRealFilesExist()throws Exception {
        wrapped.mkdir(member,"drive",DrivePath.of("/folder"));
        wrapped.upload(member,"drive",DrivePath.of("/one.txt"),new ByteArrayInputStream(new byte[]{1,2,3}),3);
        wrapped.copy(member,"drive",DrivePath.of("/one.txt"),DrivePath.of("/two.txt"));
        wrapped.rename(member,"drive",DrivePath.of("/two.txt"),"three.txt");
        wrapped.move(member,"drive",DrivePath.of("/three.txt"),DrivePath.of("/folder/three.txt"));
        assertEquals(3,wrapped.stat(member,"drive",DrivePath.of("/folder/three.txt")).orElseThrow().size());
        assertEquals(5,journal.list(member,"drive",null,20).items().size());
        assertTrue(journal.list(member,"drive",null,20).items().stream().allMatch(r->r.state().equals("SUCCEEDED")&&r.resultRef()==null));
        assertEquals(20,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_operation_event",Integer.class));
        assertFalse(jdbc.queryForList("SELECT * FROM sc_storage_operation").toString().contains("one.txt"));
    }
    @Test void featureFlagOffLeavesOriginalOperationBehaviorAndCreatesNoReceipts() {
        new JournaledDriveOperations(local,journal,false).mkdir(member,"drive",DrivePath.of("/disabled"));
        assertTrue(local.stat(member,"drive",DrivePath.of("/disabled")).isPresent());
        assertTrue(journal.list(member,"drive",null,20).items().isEmpty());
    }
    @Test void remoteAcceptOrQueuedResultIsNotMisrepresentedAsSuccess() {
        FileRecord queued=new FileRecord(DrivePath.of("/remote"),"remote",true,0,Instant.EPOCH);
        DriveOperations remote=(DriveOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DriveOperations.class},(p,m,a)->{
            if(m.getName().equals("listMounts"))return List.of(new MountView(9,"remote","remote",1L,1,"S3",true,1,Set.of("mkdir")));
            if(m.getName().equals("mkdir"))return queued;
            throw new UnsupportedOperationException();
        });
        assertSame(queued,new JournaledDriveOperations(remote,journal,true).mkdir(member,"remote",DrivePath.of("/remote")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_operation",Integer.class));
    }
    @Test void publicationThenFailureReturnsUnknownNotAnInstructionToRetry() {
        DriveOperations failing=(DriveOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DriveOperations.class},(p,m,a)->{
            if(m.getName().equals("listMounts"))return local.listMounts((Actor)a[0]);
            if(m.getName().equals("mkdir")) {
                local.mkdir((Actor)a[0],(String)a[1],(DrivePath)a[2]);
                throw new ApplicationFault("DRIVE_DATABASE_UNAVAILABLE",503,"暂时无法核验存储授权，请稍后重试");
            }
            throw new UnsupportedOperationException();
        });
        var fault=assertThrows(ApplicationFault.class,()->new JournaledDriveOperations(failing,journal,true).mkdir(member,"drive",DrivePath.of("/published-before-failure")));
        assertEquals("OPERATION_INDETERMINATE",fault.code());assertEquals(409,fault.httpStatus());
        assertTrue(fault.getMessage().contains("不要重复提交"));assertFalse(fault.getMessage().contains("稍后重试"));
        assertTrue(local.stat(member,"drive",DrivePath.of("/published-before-failure")).isPresent());
        assertEquals("INDETERMINATE",journal.list(member,"drive",null,20).items().getFirst().state());
    }
}
