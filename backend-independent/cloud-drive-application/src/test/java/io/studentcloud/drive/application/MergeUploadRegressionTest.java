package io.studentcloud.drive.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-branch regression: temporary peak reservations are not durable storage quota. */
class MergeUploadRegressionTest {
    @TempDir Path temp;
    JdbcTemplate jdbc; DataSourceTransactionManager tx; AccountDirectory accounts;
    MountService mounts; ResumableUploadService uploads; Path managed, stage, member;
    final Clock clock=Clock.systemUTC();
    final Actor admin=new Actor(1,"a1","admin",null,Role.ADMIN,null);
    final Actor owner=new Actor(3,"a3","owner",null,Role.USER,1L);
    @BeforeEach void setup() throws Exception {
        managed=Files.createDirectory(temp.resolve("managed"));stage=Files.createDirectory(temp.resolve("stage"));
        Path root=Files.createDirectory(managed.resolve("one"));member=Files.createDirectories(root.resolve("members/u3"));
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:merge_upload_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-uploads-schema.sql"),new ClassPathResource("db/independent-quota-schema.sql"),
                new ClassPathResource("db/independent-file-catalog-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);tx=new DataSourceTransactionManager(db);
        accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:owner;}public boolean workspaceEnabled(long id){return id==1;}};
        mounts=new MountService(jdbc,tx,clock,managed,accounts,new FileCatalog(jdbc,tx,clock),new QuotaLedger(jdbc,tx,clock));
        var mount=mounts.createLocal(admin,"drive","drive",root,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,upload',10,TRUE,1,1)",mount.id());
        uploads=new ResumableUploadService(jdbc,tx,clock,mounts,accounts,stage,managed,null,"node-a");
    }
    @Test void fourCopyStagingSettlesOnlyPublishedBytesInThePersistentLedger() throws Exception {
        byte[] bytes={1,2,3};String hash=ResumableUploadService.sha256(bytes);
        var session=uploads.init(owner,"drive",DrivePath.of("/one.bin"),3,List.of(hash),ResumableUploadService.manifestHash(List.of(hash)));
        assertEquals(12L,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        assertEquals("UNKNOWN",mounts.quota(owner,"drive").usageState());
        uploads.chunk(owner,session.id(),0,hash,new ByteArrayInputStream(bytes));
        assertEquals("COMPLETED",uploads.complete(owner,session.id()).state());
        assertEquals("COMPLETED",uploads.complete(owner,session.id()).state());
        assertArrayEquals(bytes,Files.readAllBytes(member.resolve("one.bin")));
        assertEquals(3L,mounts.quota(owner,"drive").usedBytes());
        assertEquals(0L,mounts.quota(owner,"drive").reservedBytes());
        assertEquals(0L,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_quota_entry WHERE category='WRITE'",Integer.class));
    }
    @Test void revokedGrantCanReleaseFourCopyStagingWithoutTouchingTheQuotaLedger() {
        byte[] bytes={1,2,3};String hash=ResumableUploadService.sha256(bytes);
        var session=uploads.init(owner,"drive",DrivePath.of("/cancel.bin"),3,List.of(hash),ResumableUploadService.manifestHash(List.of(hash)));
        assertEquals(12L,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=3");
        assertEquals("EXPIRED",uploads.cancel(owner,session.id()).state());
        assertEquals(0L,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_quota_reservation",Integer.class));
        assertFalse(Files.exists(member.resolve("cancel.bin")));
    }
}
