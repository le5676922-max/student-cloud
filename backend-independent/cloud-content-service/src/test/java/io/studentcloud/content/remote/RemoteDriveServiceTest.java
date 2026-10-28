package io.studentcloud.content.remote;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.object.ObjectStore;
import io.studentcloud.drive.object.ObjectStoreFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDriveServiceTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RemoteDriveService service;
    private MemoryFactory provider;
    private Path stage;
    private final Map<Long,Actor> identities=new HashMap<>();
    private final Actor admin=new Actor(1,"SC1","global",null,Role.ADMIN,null);
    private final Actor tenant=new Actor(2,"SC2","tenant",null,Role.TENANT_ADMIN,20L);
    private final Actor member=new Actor(3,"SC3","member",null,Role.USER,20L);
    private final Actor peer=new Actor(4,"SC4","peer",null,Role.USER,20L);
    private final Actor outsider=new Actor(5,"SC5","outside",null,Role.USER,21L);
    private RemoteConnections connections;
    private AccountDirectory accounts;

    @BeforeEach void setUp()throws Exception{
        var database=new DriverManagerDataSource("jdbc:h2:mem:remote_drive_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-remote-storage-schema.sql"),new ClassPathResource("db/independent-remote-writes-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);manager=new DataSourceTransactionManager(database);
        for(Actor actor:List.of(admin,tenant,member,peer,outsider))identities.put(actor.id(),actor);
        accounts=new AccountDirectory(){
            public Actor account(long id){Actor actor=identities.get(id);if(actor==null)throw new ApplicationFault("ACCOUNT_DISABLED",403,"账号已停用");return actor;}
            public boolean workspaceEnabled(long id){return id==20||id==21;}
        };
        jdbc.update("INSERT INTO sc_drive_mount(id,mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES(10,'remote-drive','远程云盘',20,1,'S3','remote:10',TRUE,1,1)");
        jdbc.update("INSERT INTO sc_remote_storage(mount_id,storage_type,provider,endpoint,bucket_name,region,base_prefix,path_style,credentials_ciphertext,config_version,write_verified,validated_at) VALUES(10,'s3','S3','https://s3.us-east-1.amazonaws.com','fixture-bucket','us-east-1','course/',FALSE,'synthetic-ciphertext',1,TRUE,1)");
        for(Actor actor:List.of(tenant,member,peer))grant(actor,null,"list,read,mkdir,upload");
        connections=id->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"configuration decryption cannot be nested in a provider transaction");
            var row=jdbc.queryForMap("SELECT config_version,write_verified FROM sc_remote_storage WHERE mount_id=?",id);
            return new RemoteConnections.Settings(id,new ObjectStore.Connection(ObjectStore.Provider.S3,URI.create("https://s3.us-east-1.amazonaws.com"),"fixture-bucket","us-east-1",false,"fixture-ak","fixture-sk",""),"course/",((Number)row.get("config_version")).longValue(),Boolean.TRUE.equals(row.get("write_verified")));
        };
        stage=Files.createDirectory(temp.resolve("stage"));provider=new MemoryFactory();service=newService();
    }

    @Test void freshFixedRootsIsolateAdministratorsMembersAndPeers()throws Exception{
        provider.put("course/admins/u2/admin.txt","admin");provider.put("course/members/u3/member.txt","member");provider.put("course/members/u4/peer.txt","peer");
        assertEquals(List.of("admin.txt"),service.list(tenant,"remote-drive",path("/")).stream().map(f->f.name()).toList());
        assertEquals(List.of("member.txt"),service.list(member,"remote-drive",path("/")).stream().map(f->f.name()).toList());
        assertEquals(List.of("peer.txt"),service.list(peer,"remote-drive",path("/")).stream().map(f->f.name()).toList());
        assertEquals(List.of("admins","members"),service.list(admin,"remote-drive",path("/")).stream().map(f->f.name()).toList());
        assertTrue(service.stat(member,"remote-drive",path("/admin.txt")).isEmpty());
        try(var handle=service.readWithMetadata(member,"remote-drive",path("/member.txt"))){assertEquals("member",new String(handle.input().readAllBytes(),StandardCharsets.UTF_8));}
        jdbc.update("UPDATE sc_drive_grant SET root_path='/members/u4' WHERE user_id=3 AND mount_id=10");
        assertStatus(403,()->service.list(member,"remote-drive",path("/")));
    }

    @Test void mountListingUsesOneFreshIdentityWithoutDecryptingCredentialsOrOpeningProviders(){
        metadataMount(11,"second-drive",20L,true,false);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(3,11,'/members/u3','list,read,mkdir,upload,unknown',NULL,TRUE,1,1)");
        int[] lookups={0};AccountDirectory fresh=new AccountDirectory(){
            public Actor account(long id){lookups[0]++;return accounts.account(id);}
            public boolean workspaceEnabled(long id){return accounts.workspaceEnabled(id);}
        };
        RemoteConnections unavailable=id->{throw new AssertionError("listMounts must not decrypt credentials");};
        var metadataOnly=new RemoteDriveService(jdbc,manager,Clock.systemUTC(),fresh,unavailable,provider,stage);
        var result=metadataOnly.listMounts(member);
        assertEquals(List.of(10L,11L),result.stream().map(mount->mount.id()).toList());
        assertEquals(Set.of("list","read","mkdir","upload"),result.getFirst().permissions());
        assertEquals(Set.of("list","read"),result.getLast().permissions());
        assertEquals(1,lookups[0]);assertEquals(0,provider.opened);
    }

    @Test void metadataListingHidesInvalidRootsDisabledGrantsAndOtherWorkspaces(){
        metadataMount(11,"tenant-good",20L,true,true);metadataMount(12,"foreign-drive",21L,true,true);
        metadataMount(13,"disabled-drive",20L,false,true);metadataMount(14,"revoked-drive",20L,true,true);
        for(long id:List.of(11L,12L,13L,14L))jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(2,?,'/admins/u2','list,read',NULL,TRUE,1,1)",id);
        jdbc.update("UPDATE sc_drive_grant SET root_path='/admins/u3' WHERE user_id=2 AND mount_id=10");
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=2 AND mount_id=14");
        RemoteConnections unavailable=id->{throw new AssertionError("listMounts must not decrypt credentials");};
        var metadataOnly=new RemoteDriveService(jdbc,manager,Clock.systemUTC(),accounts,unavailable,provider,stage);
        assertEquals(List.of(11L),metadataOnly.listMounts(tenant).stream().map(mount->mount.id()).toList());
        jdbc.update("UPDATE sc_drive_grant SET root_path='/members/u4' WHERE user_id=3 AND mount_id=10");
        assertTrue(metadataOnly.listMounts(member).isEmpty());assertEquals(0,provider.opened);
    }

    @Test void globalMetadataListingIncludesDisabledMountsWithEmptyCapabilities(){
        metadataMount(11,"disabled-drive",20L,false,true);metadataMount(12,"readonly-drive",null,true,false);
        RemoteConnections unavailable=id->{throw new AssertionError("listMounts must not decrypt credentials");};
        var metadataOnly=new RemoteDriveService(jdbc,manager,Clock.systemUTC(),accounts,unavailable,provider,stage);
        var result=metadataOnly.listMounts(admin);
        assertEquals(List.of(10L,11L,12L),result.stream().map(mount->mount.id()).toList());
        assertEquals(Set.of(),result.get(1).permissions());assertFalse(result.get(1).enabled());
        assertEquals(Set.of("list","read"),result.get(2).permissions());assertEquals(0,provider.opened);
    }

    @Test void crossWorkspaceAndForgedActorRolesNeverReachTheProvider(){
        grant(outsider,null,"list,read,mkdir,upload");int opened=provider.opened;
        assertStatus(403,()->service.list(outsider,"remote-drive",path("/")));
        Actor forged=new Actor(member.id(),member.accountId(),member.username(),null,Role.ADMIN,null);
        jdbc.update("DELETE FROM sc_drive_grant WHERE user_id=3 AND mount_id=10");
        assertStatus(403,()->service.list(forged,"remote-drive",path("/")));
        assertEquals(opened,provider.opened);
    }

    @Test void createOnlyWritesUsePrivateKeysAndNeverOverwriteExistingFiles()throws Exception{
        service.mkdir(member,"remote-drive",path("/notes"));
        service.upload(member,"remote-drive",path("/notes/a.txt"),bytes("original"),8);
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8),provider.objects.get("course/members/u3/notes/a.txt"));
        assertTrue(provider.objects.containsKey("course/members/u3/notes/"));
        assertStatus(409,()->service.upload(member,"remote-drive",path("/notes/a.txt"),bytes("replace!"),8));
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8),provider.objects.get("course/members/u3/notes/a.txt"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sc_remote_write_operation WHERE status='COMPLETED'",Integer.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation WHERE status<>'COMPLETED'",String.class));
        assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
        assertEquals(0,stageEntries());
    }

    @Test void quotaChargesOnlyTheMembersWholePrefixAndSerializesReservation(){
        grant(member,5L,"list,read,mkdir,upload");provider.put("course/members/u3/nested/old.txt","1234");provider.put("course/members/u4/unrelated.txt","123456789");
        assertStatus(409,()->service.upload(member,"remote-drive",path("/too-big.txt"),bytes("12"),2));
        assertFalse(provider.objects.containsKey("course/members/u3/too-big.txt"));
        service.upload(member,"remote-drive",path("/fits.txt"),bytes("1"),1);
        assertTrue(provider.objects.containsKey("course/members/u3/fits.txt"));
        assertStatus(409,()->service.prepareUpload(member,"remote-drive",path("/more.txt"),1));
    }

    @Test void revocationDuringStagingPreventsPublicationAndCleansTemporaryFile()throws Exception{
        InputStream input=new ByteArrayInputStream(new byte[]{1}){boolean revoked;@Override public synchronized int read(byte[] b,int off,int len){int count=super.read(b,off,len);if(!revoked){revoked=true;jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=3 AND mount_id=10");}return count;}};
        assertStatus(403,()->service.upload(member,"remote-drive",path("/revoked.txt"),input,1));
        assertFalse(provider.objects.containsKey("course/members/u3/revoked.txt"));assertEquals(0,provider.createCalls);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_remote_write_operation",Integer.class));assertEquals(0,stageEntries());
    }

    @Test void ambiguousPublishPersistsGuardAcrossServiceRecreationAndBlocksRetry(){
        provider.ambiguous=true;
        assertStatus(503,()->service.upload(member,"remote-drive",path("/uncertain.txt"),bytes("data"),4));
        String operation=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class);
        assertNotNull(operation);assertEquals("UNCERTAIN",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation WHERE id=?",String.class,operation));
        assertEquals(4L,jdbc.queryForObject("SELECT reserved_bytes FROM sc_remote_write_operation WHERE id=?",Long.class,operation));
        provider.ambiguous=false;int creates=provider.createCalls;
        assertStatus(409,()->newService().upload(member,"remote-drive",path("/retry.txt"),bytes("data"),4));
        assertEquals(creates,provider.createCalls);assertFalse(provider.objects.containsKey("course/members/u3/retry.txt"));
    }

    @Test void confirmedProviderConflictReleasesGuardWithoutChangingExistingObject(){
        provider.beforeCreate=()->provider.put("course/members/u3/race.txt","winner");
        assertStatus(409,()->service.upload(member,"remote-drive",path("/race.txt"),bytes("loser!"),6));
        assertArrayEquals("winner".getBytes(StandardCharsets.UTF_8),provider.objects.get("course/members/u3/race.txt"));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation",String.class));
        assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
        provider.beforeCreate=()->{};service.upload(member,"remote-drive",path("/other.txt"),bytes("ok"),2);
    }

    @Test void matchingProviderOperationMetadataConfirmsTheFrozenWriteAndReleasesGuard(){
        String operation=uncertainWrite("confirmed.txt");provider.ambiguous=false;
        var pending=service.pendingOperations(tenant,"remote-drive");
        assertEquals(1,pending.size());assertEquals(Set.of("id","actorId","path","status","createdAt","updatedAt"),pending.getFirst().keySet());
        assertEquals(operation,pending.getFirst().get("id"));assertEquals(3L,pending.getFirst().get("actorId"));assertEquals("/confirmed.txt",pending.getFirst().get("path"));
        var result=service.inspectPending(tenant,"remote-drive",operation);
        assertEquals("CONFIRMED",result.get("result"));assertEquals("COMPLETED",result.get("status"));
        assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
        assertEquals(0L,jdbc.queryForObject("SELECT reserved_bytes FROM sc_remote_write_operation WHERE id=?",Long.class,operation));
        assertTrue(service.pendingOperations(admin,"remote-drive").isEmpty());
        service.upload(member,"remote-drive",path("/next.txt"),bytes("ok"),2);
    }

    @Test void directoryMarkerInspectionUsesTheFrozenTrailingSlashKey(){
        provider.ambiguous=true;assertStatus(503,()->service.mkdir(member,"remote-drive",path("/folder")));
        String operation=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class);
        assertEquals(Boolean.TRUE,jdbc.queryForObject("SELECT is_directory FROM sc_remote_write_operation WHERE id=?",Boolean.class,operation));
        assertEquals("course/members/u3/folder/",jdbc.queryForObject("SELECT object_key FROM sc_remote_write_operation WHERE id=?",String.class,operation));
        assertEquals("CONFIRMED",service.inspectPending(admin,"remote-drive",operation).get("result"));
    }

    @Test void crashedPublishingOperationCanBePositivelyConfirmedWithoutReplay(){
        String operation=uncertainWrite("crashed.txt");jdbc.update("UPDATE sc_remote_write_operation SET status='PUBLISHING' WHERE id=?",operation);int creates=provider.createCalls;
        assertEquals("CONFIRMED",service.inspectPending(admin,"remote-drive",operation).get("result"));
        assertEquals(creates,provider.createCalls);assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
    }

    @Test void lateWriterFailureCannotRevertAProviderConfirmedCompletion(){
        var observed=new ArrayList<Map<String,Object>>();provider.ambiguous=true;
        provider.afterCreate=()->{
            String id=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class);
            observed.add(service.inspectPending(admin,"remote-drive",id));
        };
        assertStatus(503,()->service.upload(member,"remote-drive",path("/late.txt"),bytes("data"),4));
        assertEquals("CONFIRMED",observed.getFirst().get("result"));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation",String.class));
        assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
    }

    @Test void inconsistentPublishReceiptLeavesAnUncertainGuardForPositiveInspection(){
        provider.invalidReceipt=true;
        assertStatus(502,()->service.upload(member,"remote-drive",path("/receipt.txt"),bytes("data"),4));
        String operation=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class);
        assertNotNull(operation);assertEquals("UNCERTAIN",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation WHERE id=?",String.class,operation));
        assertEquals("CONFIRMED",service.inspectPending(admin,"remote-drive",operation).get("result"));
    }

    @Test void sameNameAndSizeWithDifferentOrMissingOperationMetadataCannotBeClaimed(){
        String operation=uncertainWrite("foreign.txt");
        for(String metadata:List.of(UUID.randomUUID().toString(),"")){
            provider.operationIds.put("course/members/u3/foreign.txt",metadata);
            var result=service.inspectPending(admin,"remote-drive",operation);
            assertEquals("PENDING",result.get("result"));assertEquals("UNCERTAIN",result.get("status"));
            assertEquals(operation,jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
        }
    }

    @Test void missingRemoteObjectNeverUnlocksOrAutomaticallyReplaysTheUncertainWrite(){
        String operation=uncertainWrite("missing.txt");provider.objects.remove("course/members/u3/missing.txt");int creates=provider.createCalls;
        assertEquals("PENDING",service.inspectPending(admin,"remote-drive",operation).get("result"));
        assertEquals(creates,provider.createCalls);assertEquals(operation,jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
    }

    @Test void positiveInspectionRejectsChangedConfigurationAndHeadToCommitRaces(){
        String operation=uncertainWrite("changed.txt");
        jdbc.update("UPDATE sc_remote_storage SET config_version=2 WHERE mount_id=10");
        assertEquals("PENDING",service.inspectPending(admin,"remote-drive",operation).get("result"));
        jdbc.update("UPDATE sc_remote_storage SET config_version=1 WHERE mount_id=10");
        provider.beforeHead=()->jdbc.update("UPDATE sc_remote_storage SET config_version=2 WHERE mount_id=10");
        assertStatus(409,()->service.inspectPending(admin,"remote-drive",operation));
        assertEquals(operation,jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
        assertEquals("UNCERTAIN",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation WHERE id=?",String.class,operation));
    }

    @Test void membersAndForeignWorkspaceAdministratorsCannotInspectPendingWrites(){
        String operation=uncertainWrite("private.txt");
        Actor foreignAdmin=new Actor(6,"SC6","foreign-admin",null,Role.TENANT_ADMIN,21L);identities.put(6L,foreignAdmin);
        int opened=provider.opened;
        for(Actor actor:List.of(member,peer,foreignAdmin)){
            assertStatus(403,()->service.pendingOperations(actor,"remote-drive"));
            assertStatus(403,()->service.inspectPending(actor,"remote-drive",operation));
        }
        assertEquals(opened,provider.opened);
    }

    @Test void closingReadHandlesClosesProviderStreamsAndReturnsAllConcurrencySlots()throws Exception{
        provider.put("course/members/u3/read.txt","data");
        for(int i=0;i<8;i++)try(var read=service.readWithMetadata(member,"remote-drive",path("/read.txt"))){assertEquals(4,read.input().readAllBytes().length);read.close();}
        assertEquals(8,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void capabilitiesRejectGrantRevocationDuringTheRemoteDirectoryLookup(){
        provider.put("course/members/u3/notes/","");
        provider.beforeHead=()->jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=3 AND mount_id=10");
        assertStatus(403,()->service.directoryCapabilities(member,"remote-drive",path("/notes")));
        assertEquals(provider.opened,provider.closed);assertEquals(0,provider.createCalls);
    }

    @Test void uploadAuthorizationRejectsGrantRevocationDuringTheRemoteParentLookup(){
        provider.put("course/members/u3/notes/","");
        provider.beforeHead=()->jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE,revision=revision+1 WHERE user_id=3 AND mount_id=10");
        assertStatus(403,()->service.uploadAuthorization(member,"remote-drive",path("/notes/file.txt"),1));
        assertEquals(provider.opened,provider.closed);assertEquals(0,provider.createCalls);
    }

    @Test void mismatchedProviderReadKeyFailsClosedAndReleasesEveryReadSlot(){
        provider.put("course/members/u3/read.txt","data");
        provider.readReturnedKey="course/members/u4/private.txt";
        for(int i=0;i<8;i++)assertStatus(502,()->service.readWithMetadata(member,"remote-drive",path("/read.txt")));
        assertEquals(8,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void skippingDownloadBytesCountsTowardTheVerifiedLength()throws Exception{
        provider.put("course/members/u3/read.txt","data");
        try(var read=service.readWithMetadata(member,"remote-drive",path("/read.txt"))){
            assertEquals(2,read.input().skip(2));
            assertArrayEquals("ta".getBytes(StandardCharsets.UTF_8),read.input().readAllBytes());
        }
        assertEquals(1,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void skippingCannotHideBytesBeyondTheVerifiedDownloadLength()throws Exception{
        provider.put("course/members/u3/read.txt","data");provider.readDeclaredDelta=-1;
        try(var read=service.readWithMetadata(member,"remote-drive",path("/read.txt"))){
            assertThrows(IOException.class,()->read.input().skipNBytes(4));
        }
        assertEquals(1,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void countedDownloadStreamDoesNotAllowRewindToCorruptItsLengthAccounting()throws Exception{
        provider.put("course/members/u3/read.txt","data");
        try(var read=service.readWithMetadata(member,"remote-drive",path("/read.txt"))){
            assertFalse(read.input().markSupported());read.input().mark(4);
            assertEquals('d',read.input().read());assertThrows(IOException.class,()->read.input().reset());
            assertArrayEquals("ata".getBytes(StandardCharsets.UTF_8),read.input().readAllBytes());
        }
        assertEquals(1,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void truncatedProviderStreamFailsBeforeReportingTheDownloadComplete()throws Exception{
        provider.put("course/members/u3/short.txt","data");provider.readDeclaredDelta=1;
        try(var read=service.readWithMetadata(member,"remote-drive",path("/short.txt"))){assertThrows(IOException.class,()->read.input().readAllBytes());}
        assertEquals(1,provider.streamCloses);assertEquals(provider.opened,provider.closed);
    }

    @Test void repeatedEmptyUploadReadsAreBoundedAndDoNotPublishOrCloseCallerStream()throws Exception{
        var emptyReads=new InputStream(){int reads;boolean closed;public int read(){return -1;}public int read(byte[] b,int off,int len){if(reads++<1025)return 0;if(reads==1027)return -1;b[off]=1;return 1;}public void close(){closed=true;}};
        assertStatus(400,()->service.upload(member,"remote-drive",path("/stalled.txt"),emptyReads,1));
        assertFalse(emptyReads.closed);assertEquals(0,provider.createCalls);assertEquals(0,stageEntries());
    }

    @Test void providerCloseFailureAfterCompletedWriteDoesNotCorruptTheCompletedOperation(){
        provider.closeFailure=true;
        assertDoesNotThrow(()->service.upload(member,"remote-drive",path("/published.txt"),bytes("ok"),2));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM sc_remote_write_operation",String.class));
        assertNull(jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class));
    }

    @Test void readFailureStillReleasesSlotsWhenProviderCloseAlsoFails(){
        provider.readFailure=true;provider.closeFailure=true;
        for(int i=0;i<8;i++)assertStatus(502,()->service.readWithMetadata(member,"remote-drive",path("/missing.txt")));
        assertEquals(8,provider.opened);
    }

    @Test void providerIOIsRejectedInsideCallerTransactionAndReadOnlyConfigDeniesWrite(){
        var callerTransaction=new TransactionTemplate(manager);
        callerTransaction.executeWithoutResult(tx->assertStatus(409,()->service.list(member,"remote-drive",path("/"))));
        assertEquals(0,provider.opened);
        jdbc.update("UPDATE sc_remote_storage SET write_verified=FALSE,config_version=config_version+1 WHERE mount_id=10");
        assertEquals(Set.of("list","read"),service.directoryCapabilities(member,"remote-drive",path("/")));
        assertStatus(403,()->service.upload(member,"remote-drive",path("/blocked.txt"),bytes("x"),1));
    }

    private RemoteDriveService newService(){return new RemoteDriveService(jdbc,manager,Clock.systemUTC(),accounts,connections,provider,stage);}
    private void metadataMount(long id,String key,Long workspace,boolean enabled,boolean writable){
        jdbc.update("INSERT INTO sc_drive_mount(id,mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES(?,?,?, ?,1,'S3',?, ?,1,1)",id,key,key,workspace,"remote:"+id,enabled);
        jdbc.update("INSERT INTO sc_remote_storage(mount_id,storage_type,provider,endpoint,bucket_name,region,base_prefix,path_style,credentials_ciphertext,config_version,write_verified,validated_at) SELECT ?,'s3','S3',endpoint,bucket_name,region,base_prefix,FALSE,'unreadable-fixture-ciphertext',1,?,1 FROM sc_remote_storage WHERE mount_id=10",id,writable);
    }
    private String uncertainWrite(String filename){provider.ambiguous=true;assertStatus(503,()->service.upload(member,"remote-drive",path("/"+filename),bytes("data"),4));return jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=10",String.class);}
    private void grant(Actor actor,Long quota,String permissions){
        String root=(actor.role()==Role.TENANT_ADMIN?"/admins/u":"/members/u")+actor.id();
        int updated=jdbc.update("UPDATE sc_drive_grant SET root_path=?,permissions=?,quota_bytes=?,enabled=TRUE,revision=revision+1 WHERE user_id=? AND mount_id=10",root,permissions,quota,actor.id());
        if(updated==0)jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,10,?,?,?,TRUE,1,1)",actor.id(),root,permissions,quota);
    }
    private long stageEntries()throws IOException{try(var children=Files.list(stage)){return children.count();}}
    private static DrivePath path(String value){return DrivePath.of(value);}
    private static ByteArrayInputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
    private static void assertStatus(int expected,Runnable action){assertEquals(expected,assertThrows(ApplicationFault.class,action::run).httpStatus());}

    /** Models provider key/delimiter semantics and enforces the production transaction boundary. */
    private static final class MemoryFactory implements ObjectStoreFactory {
        final Map<String,byte[]> objects=new TreeMap<>();final Map<String,String> operationIds=new HashMap<>();int opened,closed,streamCloses,createCalls;
        boolean ambiguous,closeFailure,readFailure,invalidReceipt;int readDeclaredDelta;String readReturnedKey;Runnable beforeCreate=()->{},beforeHead=()->{},afterCreate=()->{};
        void put(String key,String value){objects.put(key,value.getBytes(StandardCharsets.UTF_8));}
        private static void boundary(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"provider IO held an SQL transaction");}
        public ObjectStore open(ObjectStore.Connection config){boundary();opened++;return new ObjectStore(){
            boolean done;
            public Page list(String prefix,String delimiter,String token,int limit){
                boundary();var entries=new ArrayList<Item>();var prefixes=new LinkedHashSet<String>();
                for(var value:objects.entrySet())if(value.getKey().startsWith(prefix)){
                    String rest=value.getKey().substring(prefix.length());int slash=delimiter==null?-1:rest.indexOf(delimiter);
                    if(slash>=0)prefixes.add(prefix+rest.substring(0,slash+1));else entries.add(item(value.getKey(),value.getValue()));
                }
                return new Page(entries,prefixes.stream().toList(),null);
            }
            public Optional<Item> head(String key){boundary();beforeHead.run();return Optional.ofNullable(objects.get(key)).map(value->new Item(key,value.length,Instant.EPOCH,"etag",null,operationIds.get(key)));}
            public Read read(String key){boundary();if(readFailure||!objects.containsKey(key))throw new ApplicationFault("REMOTE_READ_FAILED",502,"远程读取失败");byte[] value=objects.get(key);return new Read(new Item(readReturnedKey==null?key:readReturnedKey,value.length+readDeclaredDelta,Instant.EPOCH,"etag",null),new ByteArrayInputStream(value){boolean done;@Override public void close()throws IOException{if(!done){done=true;streamCloses++;}super.close();}});}
            public void requireCreateOnly(){boundary();}
            public Receipt createOnly(String key,InputStream input,long size,String operation){
                boundary();createCalls++;beforeCreate.run();if(objects.containsKey(key))throw new WriteFailure("REMOTE_CONFLICT",409,"目标已存在",false);
                try{byte[] value=input.readAllBytes();assertEquals(size,value.length);objects.put(key,value);operationIds.put(key,operation);afterCreate.run();if(ambiguous)throw new WriteFailure("REMOTE_UNCERTAIN",503,"写入结果未确认",true);return new Receipt(key,invalidReceipt?size+1:size,"etag",null);}catch(IOException failure){throw new WriteFailure("REMOTE_STREAM_FAILED",503,"读取暂存失败",false);}
            }
            public void cleanupProbe(Receipt receipt){boundary();fail("drive operations cannot clean arbitrary user objects");}
            public void close(){boundary();if(!done){done=true;closed++;if(closeFailure)throw new IllegalStateException("synthetic provider close failure");}}
        };}
        private static ObjectStore.Item item(String key,byte[] value){return new ObjectStore.Item(key,value.length,Instant.EPOCH,"etag",null);}
    }
}
