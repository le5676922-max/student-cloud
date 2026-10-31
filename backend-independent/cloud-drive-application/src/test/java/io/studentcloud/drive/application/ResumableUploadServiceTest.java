package io.studentcloud.drive.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.io.ByteArrayInputStream;
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

class ResumableUploadServiceTest {
    @TempDir Path temp;
    JdbcTemplate jdbc; DataSourceTransactionManager tx; MountService mounts; AccountDirectory accounts;
    boolean verifyNoTransactionDuringIdentityLookup;
    boolean ownerEnabled=true;
    Actor currentOwner;
    Path managed, stage, courses, memberRoot; ResumableUploadService service;
    final Actor admin=new Actor(1,"a1","admin",null,Role.ADMIN,null);
    final Actor user=new Actor(3,"a3","member",null,Role.USER,1L);
    final Actor other=new Actor(4,"a4","other",null,Role.USER,1L);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"),ZoneOffset.UTC);
    final byte[] first=new byte[4*1024*1024], last=new byte[]{1,2,3};
    @BeforeEach void setup() throws Exception {
        managed=Files.createDirectory(temp.resolve("managed"));stage=Files.createDirectory(temp.resolve("stage"));courses=Files.createDirectory(temp.resolve("courses"));
        Path mounted=Files.createDirectory(managed.resolve("one"));memberRoot=Files.createDirectories(mounted.resolve("members/u3"));
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:upload_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-uploads-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);tx=new DataSourceTransactionManager(db);
        currentOwner=user;
        accounts=new AccountDirectory(){public Actor account(long id){if(verifyNoTransactionDuringIdentityLookup)assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"Upload init must not hold SQL locks during remote identity lookup");if(id==3&&!ownerEnabled)throw new ApplicationFault("42000",401,"Account unavailable");return id==1?admin:id==3?currentOwner:other;}public boolean workspaceEnabled(long id){return id==1;}};
        mounts=new MountService(jdbc,tx,clock,managed,accounts);
        var mount=mounts.createLocal(admin,"drive","local-drive",mounted,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read,upload',TRUE,1,1)",mount.id());
        service=create("node-a",clock);
    }
    ResumableUploadService create(String node,Clock time){return new ResumableUploadService(jdbc,tx,time,mounts,accounts,stage,managed,courses,node);}
    List<String> hashes(){return List.of(ResumableUploadService.sha256(first),ResumableUploadService.sha256(last));}
    ResumableUploadService.View init(String name){var hashes=hashes();return service.init(user,"local-drive",DrivePath.of("/"+name),first.length+last.length,hashes,ResumableUploadService.manifestHash(hashes));}
    @Test void resumesMissingChunksAcrossServiceRestartAndCompletionIsIdempotent() throws Exception {
        var session=init("中文.bin");
        service.chunk(user,session.id(),0,hashes().getFirst(),new ByteArrayInputStream(first));
        service=create("node-a",clock);
        assertEquals(session.id(),init("中文.bin").id());
        assertEquals(List.of(0),service.status(user,session.id()).receivedChunks());
        assertEquals(List.of(1),service.status(user,session.id()).missingChunks());
        service.chunk(user,session.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        assertEquals("COMPLETED",service.complete(user,session.id()).state());
        assertEquals("COMPLETED",service.complete(user,session.id()).state());
        assertEquals(first.length+last.length,Files.size(memberRoot.resolve("中文.bin")));
        assertFault(409,()->init("中文.bin"));
        Files.delete(memberRoot.resolve("中文.bin"));
        assertNotEquals(session.id(),init("中文.bin").id());
    }
    @Test void initAndResumePerformIdentityAndQuotaPreflightOutsideTheGlobalBudgetTransaction() throws Exception {
        verifyNoTransactionDuringIdentityLookup=true;
        var session=init("bounded.bin");assertEquals(session.id(),init("bounded.bin").id());
        jdbc.update("UPDATE sc_upload_session SET state='COMPLETING' WHERE id=?",session.id());
        Files.writeString(memberRoot.resolve("bounded.bin"),"possibly published");
        assertEquals(session.id(),init("bounded.bin").id());
        assertEquals("COMPLETING",service.status(user,session.id()).state());
    }
    @Test void rejectsWrongContentOwnerNodeAndRevokedGrant() {
        var s=init("one");
        assertFault(400,()->service.chunk(user,s.id(),0,hashes().getFirst(),new ByteArrayInputStream(last)));
        assertTrue(service.status(user,s.id()).receivedChunks().isEmpty());
        assertFault(404,()->service.status(other,s.id()));
        assertFault(409,()->create("node-b",clock).status(user,s.id()));
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
        assertFault(403,()->service.status(user,s.id()));
    }
    @Test void changedAuthorizationNeverResumesAndIncompleteCompletionDoesNotPublish() {
        var s=init("one");
        assertFault(409,()->service.complete(user,s.id()));
        jdbc.update("UPDATE sc_drive_grant SET revision=revision+1 WHERE user_id=3");
        assertFault(409,()->service.status(user,s.id()));
        assertFalse(Files.exists(memberRoot.resolve("one")));
    }
    @Test void pendingPublicationIsNeverReplayedAndTargetIsNeverOverwritten() throws Exception {
        var s=init("one");
        jdbc.update("UPDATE sc_upload_session SET state='COMPLETING' WHERE id=?",s.id());
        assertEquals("COMPLETING",service.complete(user,s.id()).state());
        assertFalse(Files.exists(memberRoot.resolve("one")));
        var next=init("two");
        service.chunk(user,next.id(),0,hashes().getFirst(),new ByteArrayInputStream(first));
        service.chunk(user,next.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        Files.writeString(memberRoot.resolve("two"),"existing");
        assertFault(409,()->service.complete(user,next.id()));
        assertEquals("existing",Files.readString(memberRoot.resolve("two")));
    }
    @Test void limitsActiveAndPendingBytesAndExpiresOldSessions() {
        init("one");init("two");init("three");
        assertFault(429,()->init("four"));
        service=create("node-a",Clock.offset(clock,Duration.ofHours(25)));
        assertEquals("OPEN",init("four").state());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session WHERE state='OPEN'",Integer.class));
    }
    @Test void stagingMustBeOutsideBothBrowsableAndCourseRoots() {
        assertThrows(IllegalArgumentException.class,()->new ResumableUploadService(jdbc,tx,clock,mounts,accounts,managed,managed,courses,"node-a"));
        assertThrows(IllegalArgumentException.class,()->new ResumableUploadService(jdbc,tx,clock,mounts,accounts,courses,managed,courses,"node-a"));
    }
    @Test void byteReservationsPreventAThirdLargePendingUploadAndCancelReleasesOnlyPrivateBytes() {
        var hashes=new ArrayList<String>();for(int i=0;i<13;i++)hashes.add(hashes().getFirst());
        String manifest=ResumableUploadService.manifestHash(hashes);
        var one=service.init(user,"local-drive",DrivePath.of("/large1"),50L*1024*1024,hashes,manifest);
        service.init(user,"local-drive",DrivePath.of("/large2"),50L*1024*1024,hashes,manifest);
        assertFault(429,()->init("tiny"));
        assertEquals("EXPIRED",service.cancel(user,one.id()).state());
        assertEquals("OPEN",init("tiny").state());
        assertFalse(Files.exists(memberRoot.resolve("large1")));
    }
    @Test void corruptionIsDetectedBeforePublicationAndActiveAttemptCannotBeReplaced() throws Exception {
        var session=init("one");
        jdbc.update("UPDATE sc_upload_session SET attempt_token=?,attempt_until=? WHERE id=?",UUID.randomUUID().toString(),clock.millis()+10000,session.id());
        assertFault(409,()->service.chunk(user,session.id(),0,hashes().getFirst(),new ByteArrayInputStream(first)));
        jdbc.update("UPDATE sc_upload_session SET attempt_token=NULL,attempt_until=0 WHERE id=?",session.id());
        service.chunk(user,session.id(),0,hashes().getFirst(),new ByteArrayInputStream(first));
        service.chunk(user,session.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        String file=jdbc.queryForObject("SELECT file_name FROM sc_upload_chunk WHERE session_id=? AND chunk_index=1",String.class,session.id());
        Files.write(stage.resolve(session.id()).resolve(file),new byte[]{3,2,1});
        assertFault(409,()->service.complete(user,session.id()));
        assertEquals("OPEN",service.status(user,session.id()).state());
        assertFalse(Files.exists(memberRoot.resolve("one")));
    }
    @Test void cancelledHistoryDoesNotBlockOtherAccountsAndCreationIsOwnerBounded() throws Exception {
        List<String> hashes=List.of(ResumableUploadService.sha256(new byte[]{1}));
        String manifest=ResumableUploadService.manifestHash(hashes);
        // Every cancellation is a real service call with private folder cleanup and a durable receipt.
        for(int index=0;index<1000;index++){
            var created=service.init(user,"local-drive",DrivePath.of("/cancel-"+index),1,hashes,manifest);
            assertEquals("EXPIRED",service.cancel(user,created.id()).state());
        }
        assertEquals(0,jdbc.queryForObject("SELECT SUM(staging_reserved) FROM sc_upload_session",Long.class));
        Files.createDirectories(memberRoot.getParent().resolve("u4"));
        long mount=jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE mount_key='local-drive'",Long.class);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(4,?,'/members/u4','list,read,upload',TRUE,1,1)",mount);
        assertEquals("OPEN",service.init(other,"local-drive",DrivePath.of("/other.bin"),1,hashes,manifest).state());
        var blocked=assertThrows(ApplicationFault.class,()->service.init(user,"local-drive",DrivePath.of("/excess.bin"),1,hashes,manifest));
        assertEquals(429,blocked.httpStatus());assertEquals("UPLOAD_CREATION_BUDGET",blocked.code());
        // New service objects share SQL history; restart cannot reset the per-account creation budget.
        assertEquals("UPLOAD_CREATION_BUDGET",assertThrows(ApplicationFault.class,()->create("node-a",clock).init(user,"local-drive",DrivePath.of("/restart.bin"),1,hashes,manifest)).code());
    }
    @Test void uncertainPublicationRetainsItsReservationAndIdentityAfterExpiry() throws Exception {
        var session=init("pending.bin");
        jdbc.update("UPDATE sc_upload_session SET state='COMPLETING' WHERE id=?",session.id());
        Files.writeString(memberRoot.resolve("pending.bin"),"possibly published");
        service=create("node-a",Clock.offset(clock,Duration.ofHours(25)));
        service.cleanupExpired();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session WHERE id=?",Integer.class,session.id()));
        assertEquals(first.length+last.length,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        assertEquals("COMPLETING",service.status(user,session.id()).state());
        assertEquals(session.id(),init("pending.bin").id());
        assertEquals("COMPLETING",service.complete(user,session.id()).state());
        assertFault(409,()->service.cancel(user,session.id()));
        assertEquals("possibly published",Files.readString(memberRoot.resolve("pending.bin")));
    }
    @Test void terminalRowsWithUnreleasedPrivateBytesStillConsumeActorSlots() {
        var one=init("one");var two=init("two");var three=init("three");
        // This node cannot certify another node's leftover chunk cleanup.
        jdbc.update("UPDATE sc_upload_session SET state='EXPIRED',node_id='node-b' WHERE id IN(?,?,?)",one.id(),two.id(),three.id());
        assertFault(429,()->init("four"));
    }
    @Test void taskOwnerCanCancelAfterDirectoryGrantIsRevokedWithoutReadingThatDirectory() throws Exception {
        var session=init("revoked.bin");
        service.chunk(user,session.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=3");
        // Removing the old visible root must not make the user's own private cancellation impossible.
        Files.delete(memberRoot);
        assertFault(404,()->service.cancel(other,session.id()));
        assertEquals("EXPIRED",service.cancel(user,session.id()).state());
        assertEquals("EXPIRED",service.cancel(user,session.id()).state());
        assertEquals(0,jdbc.queryForObject("SELECT staging_reserved FROM sc_upload_session WHERE id=?",Long.class,session.id()));
        try(var parts=Files.list(stage.resolve(session.id()))){assertEquals(0,parts.count());}
    }
    @Test void knownQuotaOverflowIsRejectedBeforeCreatingTheUploadSession() throws Exception {
        jdbc.update("UPDATE sc_drive_grant SET quota_bytes=5 WHERE user_id=3");
        Files.write(memberRoot.resolve("existing.bin"),new byte[]{1,2,3,4});
        var hashes=List.of(ResumableUploadService.sha256(new byte[]{1,2}));
        String manifest=ResumableUploadService.manifestHash(hashes);
        var failed=assertThrows(ApplicationFault.class,()->service.init(user,"local-drive",DrivePath.of("/too-large.bin"),2,hashes,manifest));
        assertEquals("DRIVE_QUOTA_EXCEEDED",failed.code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session",Integer.class));
        try(var pending=Files.list(stage)){assertEquals(0,pending.count());}
    }
    @Test void privateCancellationReloadsAndBindsTheAccountEvenAfterGrantRevocation() throws Exception {
        var session=init("identity.bin");
        service.chunk(user,session.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        String part=jdbc.queryForObject("SELECT file_name FROM sc_upload_chunk WHERE session_id=? AND chunk_index=1",String.class,session.id());
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
        assertFault(401,()->service.cancel(null,session.id()));
        ownerEnabled=false;
        assertFault(401,()->service.cancel(user,session.id()));
        ownerEnabled=true;
        currentOwner=new Actor(user.id(),user.accountId(),user.username(),null,Role.USER,2L);
        assertFault(401,()->service.cancel(user,session.id()));
        currentOwner=other;
        assertFault(401,()->service.cancel(user,session.id()));
        currentOwner=new Actor(user.id(),"replacement-account",user.username(),null,Role.USER,1L);
        assertFault(401,()->service.cancel(user,session.id()));
        currentOwner=user;
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM sc_upload_session WHERE id=?",String.class,session.id()));
        assertTrue(Files.exists(stage.resolve(session.id()).resolve(part)));
        assertEquals("EXPIRED",service.cancel(user,session.id()).state());
    }
    @Test void privateCancellationRetainsNodePublicationAndAttemptBarriersAfterGrantRevision() throws Exception {
        var session=init("barriers.bin");
        service.chunk(user,session.id(),1,hashes().get(1),new ByteArrayInputStream(last));
        Files.writeString(memberRoot.resolve("barriers.bin"),"preserve the target");
        jdbc.update("UPDATE sc_drive_grant SET revision=revision+1 WHERE user_id=3");
        assertEquals("UPLOAD_NODE_CHANGED",assertThrows(ApplicationFault.class,()->create("node-b",clock).cancel(user,session.id())).code());
        jdbc.update("UPDATE sc_upload_session SET state='COMPLETING' WHERE id=?",session.id());
        assertEquals("UPLOAD_PUBLICATION_PENDING",assertThrows(ApplicationFault.class,()->service.cancel(user,session.id())).code());
        jdbc.update("UPDATE sc_upload_session SET state='OPEN',attempt_token=?,attempt_until=? WHERE id=?",UUID.randomUUID().toString(),clock.millis()+10000,session.id());
        assertEquals("UPLOAD_BUSY",assertThrows(ApplicationFault.class,()->service.cancel(user,session.id())).code());
        jdbc.update("UPDATE sc_upload_session SET attempt_token=NULL,attempt_until=0 WHERE id=?",session.id());
        assertEquals("EXPIRED",service.cancel(user,session.id()).state());
        assertEquals("preserve the target",Files.readString(memberRoot.resolve("barriers.bin")));
    }
    @Test void publicationRechecksQuotaWhenAnotherPendingTaskHasConsumedTheCapacity() throws Exception {
        jdbc.update("UPDATE sc_drive_grant SET quota_bytes=2 WHERE user_id=3");
        byte[] bytes={1,2};var hashes=List.of(ResumableUploadService.sha256(bytes));
        String manifest=ResumableUploadService.manifestHash(hashes);
        var one=service.init(user,"local-drive",DrivePath.of("/one.bin"),2,hashes,manifest);
        var two=service.init(user,"local-drive",DrivePath.of("/two.bin"),2,hashes,manifest);
        service.chunk(user,one.id(),0,hashes.getFirst(),new ByteArrayInputStream(bytes));
        service.chunk(user,two.id(),0,hashes.getFirst(),new ByteArrayInputStream(bytes));
        assertEquals("COMPLETED",service.complete(user,one.id()).state());
        assertEquals("DRIVE_QUOTA_EXCEEDED",assertThrows(ApplicationFault.class,()->service.complete(user,two.id())).code());
        assertFalse(Files.exists(memberRoot.resolve("two.bin")));
        assertArrayEquals(bytes,Files.readAllBytes(memberRoot.resolve("one.bin")));
        assertEquals("OPEN",service.status(user,two.id()).state());
        assertEquals("EXPIRED",service.cancel(user,two.id()).state());
    }
    static void assertFault(int status,Runnable action){assertEquals(status,assertThrows(ApplicationFault.class,action::run).httpStatus());}
}
