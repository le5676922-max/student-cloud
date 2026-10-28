package io.studentcloud.content.application;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.WorkspaceCloudService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class StorageAdministrationServiceTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private StorageAdministrationService service;
    private Actor global,tenant,member,otherMember,peer;
    private java.nio.file.Path managed;
    private DataSourceTransactionManager transactionManager;
    private AccountDirectory accounts;
    private MountService localMounts;
    private WorkspaceCloudService clouds;
    private Clock testClock;

    @BeforeEach void setUp() throws Exception {
        var database=new DriverManagerDataSource("jdbc:h2:mem:storage_admin_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-cloud-access-schema.sql"),
                new ClassPathResource("db/independent-storage-administration-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);var tx=new DataSourceTransactionManager(database);var clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneOffset.UTC);
        global=new Actor(1,"SCGLOBAL","global","global@example.test",Role.ADMIN,null);
        tenant=new Actor(2,"SCTENANT","tenant","tenant@example.test",Role.TENANT_ADMIN,20L);
        member=new Actor(3,"SCMEMBER","member","member@example.test",Role.USER,20L);
        otherMember=new Actor(4,"SCOTHER","other","other@example.test",Role.USER,21L);
        peer=new Actor(5,"SCPEER","peer","peer@example.test",Role.USER,20L);
        AccountDirectory directory=new AccountDirectory(){public Actor account(long id){return switch((int)id){case 1->global;case 2->tenant;case 3->member;case 4->otherMember;case 5->peer;default->throw new IllegalArgumentException("missing account");};}public boolean workspaceEnabled(long workspaceId){return workspaceId==20||workspaceId==21;}};
        managed=Files.createDirectory(temp.resolve("managed"));var mountService=new MountService(jdbc,tx,clock,managed,directory);
        var workspaceCloud=new WorkspaceCloudService(jdbc,tx,clock,managed,directory);
        service=new StorageAdministrationService(jdbc,tx,clock,directory,mountService,workspaceCloud,managed.toString());
        transactionManager=tx;accounts=directory;localMounts=mountService;clouds=workspaceCloud;testClock=clock;
    }

    @Test void globallyAssignedWorkspaceStorageProvisionsAnIsolatedAdministratorRootWithoutGrantingMembers() throws Exception {
        var root=Files.createDirectory(managed.resolve("provision"));
        // Production wiring supplies the platform-owned administrator directory.
        var production=new StorageAdministrationService(jdbc,transactionManager,testClock,accounts,localMounts,clouds,managed.toString(),id->List.of(tenant));
        long id=production.save(global,config("provision",root,true));
        assertEquals("/admins/u2",jdbc.queryForObject("SELECT root_path FROM sc_drive_grant WHERE user_id=2 AND mount_id=?",String.class,id));
        assertTrue(Files.isDirectory(root.resolve("admins/u2")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id IN(3,4,5)",Integer.class));
        assertEquals(1,production.list(tenant).size());
        assertEquals(1,((List<?>)production.memberPermissions(tenant,member.id()).get("storages")).size());
        assertFalse(production.list(tenant).toString().contains(root.toString()));
    }

    @Test void localOnlyConnectionProbeHonorsReadWriteChoiceAndCleansOnlyItsOwnProbe() throws Exception {
        var root=Files.createDirectory(managed.resolve("probe"));
        var existing=Files.writeString(root.resolve(".student-cloud-preflight-existing.tmp"),"existing data");
        var readOnly=new LinkedHashMap<>(config("probe",root,true));readOnly.put("verifyWrite",false);
        var readResult=service.testConnection(global,readOnly);
        assertEquals(Boolean.TRUE,readResult.get("passed"));assertTrue(readResult.get("message").toString().contains("只读"));
        assertEquals(1,entries(root));assertEquals("existing data",Files.readString(existing));
        var writable=new LinkedHashMap<>(readOnly);writable.put("verifyWrite",true);
        var writeResult=service.testConnection(global,writable);
        assertEquals(Boolean.TRUE,writeResult.get("passed"));assertEquals(Boolean.TRUE,writeResult.get("cleanup"));
        assertTrue(writeResult.get("message").toString().contains("读写测试成功"));
        assertEquals(1,entries(root));assertEquals("existing data",Files.readString(existing));
        long storageId=service.save(global,config("probe-storage",root,true));
        assertEquals(Boolean.FALSE,service.list(global).getFirst().get("cacheAvailable"));
        assertThrows(ApplicationFault.class,()->service.setCache(global,storageId,true));
        assertThrows(ApplicationFault.class,()->service.save(global,Map.of("type","local","key","probe-cache","name","probe-cache","tenantId",20,"enable",true,"enableCache",true,"storageSourceAllParam",Map.of("filePath",root.toString()))));
        assertThrows(ApplicationFault.class,()->service.save(tenant,config("denied",root,true)));
    }

    @Test void creatingDisabledStoragePersistsTheRequestedStateBeforeItBecomesVisible() throws Exception {
        var root=Files.createDirectory(managed.resolve("disabled"));
        long id=service.save(global,config("disabled",root,false));
        assertEquals(Boolean.FALSE,jdbc.queryForObject("SELECT enabled FROM sc_drive_mount WHERE id=?",Boolean.class,id));
        assertEquals(Boolean.FALSE,service.storage(global,id).get("enable"));
        assertEquals(Boolean.FALSE,service.list(global).getFirst().get("enable"));
        assertTrue(service.workspaceClouds(member).isEmpty());
        service.setEnabled(global,id,true);
        assertEquals(Boolean.TRUE,service.storage(global,id).get("enable"));
        var update=new LinkedHashMap<>(config("disabled",root,false));update.put("id",id);
        service.save(global,update);
        assertEquals(Boolean.FALSE,service.storage(global,id).get("enable"));
    }

    @Test void unsupportedProvidersFailExplicitlyWithoutSavingOrProbingAnyLocalFiles() throws Exception {
        var root=Files.createDirectory(managed.resolve("unsupported"));
        var sentinel=Files.writeString(root.resolve("keep.txt"),"keep");
        for(String provider:List.of("s3","webdav","ftp","sftp")){
            var input=new LinkedHashMap<>(config("unsupported-"+provider,root,true));input.put("type",provider);input.put("verifyWrite",true);
            assertStatus(501,()->service.storageParams(global,provider));
            assertStatus(501,()->service.testConnection(global,input));
            assertStatus(501,()->service.save(global,input));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount",Integer.class));
        assertEquals(1,entries(root));assertEquals("keep",Files.readString(sentinel));
    }

    @Test void managedRootOutsidePathsAndMissingDirectoriesCannotBeProbedOrMounted() throws Exception {
        var outside=Files.createDirectory(temp.resolve("outside"));
        var sentinel=Files.writeString(outside.resolve("keep.txt"),"keep");
        for(Path root:List.of(managed,outside,managed.resolve("../outside"),managed.resolve("missing"))){
            var input=new LinkedHashMap<>(config("invalid-root",root,true));input.put("verifyWrite",true);
            assertStatus(403,()->service.testConnection(global,input));
            assertStatus(403,()->service.save(global,input));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount",Integer.class));
        assertFalse(Files.exists(managed.resolve("missing")));
        assertEquals(1,entries(outside));assertEquals("keep",Files.readString(sentinel));
    }

    @Test void localApplicationStoresPurposeAndNeverAcceptsApplicantChosenRoot() {
        var request=service.requestLocal(tenant,"课程资料归档");
        assertEquals("PENDING",request.status());assertEquals("课程资料归档",request.reason());
        assertEquals("服务器本地云盘",request.name());
        assertThrows(ApplicationFault.class,()->service.requestLocal(member,"/etc"));
    }

    @Test void globalCanCreateOnlyManagedDisjointRootsAndTenantListNeverRevealsPhysicalRoot() throws Exception {
        var root=Files.createDirectory(managed.resolve("workspace"));
        long id=service.save(global,config("workspace",root,true));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount",Integer.class));
        assertThrows(ApplicationFault.class,()->service.save(global,config("nested",Files.createDirectory(root.resolve("nested")),true)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount",Integer.class));
        assertThrows(ApplicationFault.class,()->service.storage(tenant,id));
        assertFalse(service.list(tenant).stream().anyMatch(row->row.containsKey("physicalRoot")||row.containsKey("filePath")));
        assertTrue(service.storage(global,id).get("storageSourceAllParam").toString().contains(root.toString()));
        assertEquals(1,service.list(global).size());
    }

    @Test void deletionRequiresConfirmationAndDisableAndPreservesAllFilesAndRemovesGrants() throws Exception {
        var root=Files.createDirectory(managed.resolve("delete-me"));var data=Files.writeString(root.resolve("keep.txt"),"keep");
        long id=service.save(global,config("delete-me",root,true));
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,1)",3L,id,"/members/u3","list",null,true);
        assertThrows(ApplicationFault.class,()->service.delete(global,id,true));
        service.setEnabled(global,id,false);
        assertThrows(ApplicationFault.class,()->service.delete(global,id,false));
        service.delete(global,id,true);
        assertEquals("keep",Files.readString(data));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_mount",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant",Integer.class));
    }

    @Test void deletionRetainsMountGrantsMetadataAndEveryStatusOfMemberRequestHistory() throws Exception {
        for(String requestStatus:List.of("PENDING","APPROVED","REJECTED")){
            String key="history-"+requestStatus.toLowerCase(java.util.Locale.ROOT);
            var root=Files.createDirectory(managed.resolve(key));var data=Files.writeString(root.resolve("keep.txt"),"keep");
            long id=service.save(global,config(key,root,false));
            jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,1)",3L,id,"/members/u3","list",null,true);
            jdbc.update("INSERT INTO sc_cloud_access_request(requester_id,mount_id,workspace_id,status,pending_key,reason,created_at) VALUES(?,?,20,?,?,?,1)",3L,id,requestStatus,"PENDING".equals(requestStatus)?"3:"+id:null,"preserve history");
            ApplicationFault failure=assertThrows(ApplicationFault.class,()->service.delete(global,id,true));
            assertEquals(409,failure.httpStatus());assertEquals("STORAGE_REQUEST_HISTORY_EXISTS",failure.code());
            assertTrue(failure.getMessage().contains("申请历史"));
            assertEquals(requestStatus,jdbc.queryForObject("SELECT status FROM sc_cloud_access_request WHERE mount_id=?",String.class,id));
            assertEquals("preserve history",jdbc.queryForObject("SELECT reason FROM sc_cloud_access_request WHERE mount_id=?",String.class,id));
            assertEquals(Boolean.FALSE,jdbc.queryForObject("SELECT enabled FROM sc_drive_mount WHERE id=?",Boolean.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE mount_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_admin_metadata WHERE mount_id=?",Integer.class,id));
            assertEquals("keep",Files.readString(data));
        }
    }

    @Test void sortingPreservesRequestedOrderAndRejectsDuplicateIdsWithoutChangingIt() throws Exception {
        long first=service.save(global,config("sort-first",Files.createDirectory(managed.resolve("sort-first")),true));
        long second=service.save(global,config("sort-second",Files.createDirectory(managed.resolve("sort-second")),true));
        service.sort(global,List.of(Map.of("id",second),Map.of("id",first)));
        assertEquals(List.of(second,first),service.list(global).stream().map(row->row.get("id")).toList());
        assertStatus(400,()->service.sort(global,List.of(Map.of("id",first),Map.of("id",first))));
        assertEquals(List.of(second,first),service.list(global).stream().map(row->row.get("id")).toList());
    }
    @Test void workspaceAdministratorsCanCheckAliasesWithoutReceivingAnyStorageConfiguration() throws Exception {
        var root=Files.createDirectory(managed.resolve("alias"));service.save(global,config("owned-alias",root,true));
        assertTrue(service.existsKey(tenant,"owned-alias"));assertFalse(service.existsKey(tenant,"unused-alias"));
        assertStatus(403,()->service.existsKey(member,"owned-alias"));
    }
    @Test void globalOrderingIncludesRemoteMetadataWithoutCallingTheLocalDriver() throws Exception {
        long local=service.save(global,config("sort-local",Files.createDirectory(managed.resolve("sort-local")),true));
        jdbc.update("INSERT INTO sc_drive_mount(mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES('sort-remote','Remote',20,2,'S3','remote',TRUE,1,1)");
        long remote=jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE mount_key='sort-remote'",Long.class);
        service.sort(global,List.of(Map.of("id",remote),Map.of("id",local)));
        assertEquals(0,jdbc.queryForObject("SELECT order_num FROM sc_storage_admin_metadata WHERE mount_id=?",Integer.class,remote));
        assertEquals(1,jdbc.queryForObject("SELECT order_num FROM sc_storage_admin_metadata WHERE mount_id=?",Integer.class,local));
    }

    @Test void memberPermissionWritesStayInsideLiveWorkspaceAndIntersectAdministratorGrant() throws Exception {
        var root=Files.createDirectory(managed.resolve("permission-root"));long id=service.save(global,config("permission",root,true));
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,1)",2L,id,"/admins/u2","list,read,upload",null,true);
        var tenantRows=service.list(tenant);assertEquals(1,tenantRows.size());assertFalse(tenantRows.getFirst().containsKey("physicalRoot"));assertFalse(tenantRows.getFirst().containsKey("filePath"));
        var before=service.memberPermissions(tenant,3);
        assertEquals(3L,before.get("userId"));assertEquals("/members/u3",((Map<?,?>)((List<?>)before.get("storages")).getFirst()).get("allowedRootPath"));
        var input=new StorageAdministrationService.MemberPermissionsInput(List.of("list"),List.of(new StorageAdministrationService.MemberStorageInput(id,"/members/u3",true,List.of("list","read"),0L)));
        service.saveMemberPermissions(tenant,3,input);
        assertEquals("list,read",jdbc.queryForObject("SELECT permissions FROM sc_drive_grant WHERE user_id=3 AND mount_id=?",String.class,id));
        assertEquals(0L,jdbc.queryForObject("SELECT quota_bytes FROM sc_drive_grant WHERE user_id=3 AND mount_id=?",Long.class,id));
        assertThrows(ApplicationFault.class,()->service.saveMemberPermissions(tenant,4,input));
        var outside=new StorageAdministrationService.MemberPermissionsInput(List.of("delete"),input.storages());
        assertThrows(ApplicationFault.class,()->service.saveMemberPermissions(tenant,3,outside));
        assertThrows(ApplicationFault.class,()->service.saveMemberPermissions(global,3,input));
    }

    @Test void eachMembersSavedPresetIsIsolatedFromPeersAndTheEditor() throws Exception {
        var root=Files.createDirectory(managed.resolve("preset-root"));long id=service.save(global,config("preset",root,true));
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,1)",2L,id,"/admins/u2","list,read,upload",null,true);
        service.saveMemberPermissions(tenant,3,new StorageAdministrationService.MemberPermissionsInput(List.of("list","read"),List.of()));
        assertEquals(List.of("list","read"),service.memberPermissions(tenant,3).get("defaultPermissions"));
        assertEquals(List.of(),service.memberPermissions(tenant,5).get("defaultPermissions"));
        service.saveMemberPermissions(tenant,5,new StorageAdministrationService.MemberPermissionsInput(List.of("list"),List.of()));
        service.saveMemberPermissions(tenant,3,new StorageAdministrationService.MemberPermissionsInput(List.of("list","upload"),List.of()));
        assertEquals(List.of("list","upload"),service.memberPermissions(tenant,3).get("defaultPermissions"));
        assertEquals(List.of("list"),service.memberPermissions(tenant,5).get("defaultPermissions"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_member_permission_defaults WHERE user_id=2",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_member_permission_defaults",Integer.class));
        // A preset is metadata only: changing it must not silently create a cloud grant.
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id IN (3,5)",Integer.class));
        assertThrows(ApplicationFault.class,()->service.memberPermissions(tenant,4));
    }

    @Test void concurrentMemberEditsWithOppositeStorageOrderCompleteWithoutLosingEitherGrant() throws Exception {
        long first=service.save(global,config("concurrent-first",Files.createDirectory(managed.resolve("concurrent-first")),true));
        long second=service.save(global,config("concurrent-second",Files.createDirectory(managed.resolve("concurrent-second")),true));
        for(long id:List.of(first,second))jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,1)",2L,id,"/admins/u2","list,read",null,true);
        var memberInput=new StorageAdministrationService.MemberPermissionsInput(List.of("list"),List.of(
                new StorageAdministrationService.MemberStorageInput(first,"/members/u3",true,List.of("list","read"),10L),
                new StorageAdministrationService.MemberStorageInput(second,"/members/u3",true,List.of("list","read"),10L)));
        var peerInput=new StorageAdministrationService.MemberPermissionsInput(List.of("list"),List.of(
                new StorageAdministrationService.MemberStorageInput(second,"/members/u5",true,List.of("list"),20L),
                new StorageAdministrationService.MemberStorageInput(first,"/members/u5",true,List.of("list"),20L)));
        var workers=Executors.newFixedThreadPool(2);
        try{
            for(int round=0;round<8;round++){
                var start=new CountDownLatch(1);
                var memberSave=workers.submit(()->{start.await();service.saveMemberPermissions(tenant,3,memberInput);return null;});
                var peerSave=workers.submit(()->{start.await();service.saveMemberPermissions(tenant,5,peerInput);return null;});
                start.countDown();memberSave.get(10,TimeUnit.SECONDS);peerSave.get(10,TimeUnit.SECONDS);
            }
        }finally{workers.shutdownNow();}
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id=3 AND permissions='list,read' AND quota_bytes=10 AND enabled=TRUE",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id=5 AND permissions='list' AND quota_bytes=20 AND enabled=TRUE",Integer.class));
        assertTrue(Files.isDirectory(managed.resolve("concurrent-first/members/u3")));
        assertTrue(Files.isDirectory(managed.resolve("concurrent-second/members/u5")));
    }

    private Map<String,Object> config(String key,java.nio.file.Path root,boolean enabled){return Map.of("type","local","key",key,"name",key,"tenantId",20,"enable",enabled,"storageSourceAllParam",Map.of("filePath",root.toString()));}
    private static void assertStatus(int status,Runnable action){assertEquals(status,assertThrows(ApplicationFault.class,action::run).httpStatus());}
    private static long entries(java.nio.file.Path path)throws Exception{try(var files=Files.list(path)){return files.count();}}
}
