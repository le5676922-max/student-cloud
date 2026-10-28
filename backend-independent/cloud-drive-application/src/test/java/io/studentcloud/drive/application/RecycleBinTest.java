package io.studentcloud.drive.application;
import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
class RecycleBinTest {
 @TempDir Path temp;JdbcTemplate jdbc;MountService mounts;Path privateRoot;String failStatus;
 Actor admin=new Actor(1,"root","root",null,Role.ADMIN,null),user=new Actor(3,"u3","u3",null,Role.USER,7L),peer=new Actor(4,"u4","u4",null,Role.USER,7L);
 @BeforeEach void setup()throws Exception{
  Path managed=Files.createDirectory(temp.resolve("managed")),physical=Files.createDirectory(managed.resolve("drive"));privateRoot=Files.createDirectories(physical.resolve("members/u3"));Files.createDirectories(physical.resolve("members/u4"));
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:trash_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),new ClassPathResource("db/independent-file-catalog-schema.sql"),new ClassPathResource("db/independent-recycle-schema.sql")).execute(ds);
  jdbc=new JdbcTemplate(ds){@Override public int update(String sql,Object...args){if(failStatus!=null&&sql.contains("status='"+failStatus+"'"))throw new org.springframework.dao.DataAccessResourceFailureException("Injected database outage after filesystem change");return super.update(sql,args);}};var tx=new DataSourceTransactionManager(ds);AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:id==3?user:peer;}public boolean workspaceEnabled(long id){return id==7;}};
  mounts=new MountService(jdbc,tx,Clock.systemUTC(),managed,accounts,new FileCatalog(jdbc,tx,Clock.systemUTC()));
  var mount=mounts.createLocal(admin,"Drive","trash-drive",physical,7L);
  for(long id:List.of(3L,4L))jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at,quota_bytes) VALUES(?,?,?,'list,read,upload,delete,mkdir',TRUE,1,1,8)",id,mount.id(),"/members/u"+id);
  Files.writeString(privateRoot.resolve("notes.txt"),"original");
 }
 @Test void softDeletePreservesQuotaAndCanRestore(){
  mounts.delete(user,"trash-drive",DrivePath.of("/notes.txt"));assertTrue(mounts.list(user,"trash-drive",DrivePath.of("/")).isEmpty());
  var row=mounts.recycleBin(user,"trash-drive").getFirst();assertEquals("TRASHED",row.status());assertEquals(8,row.size());
  assertEquals(409,assertThrows(ApplicationFault.class,()->mounts.upload(user,"trash-drive",DrivePath.of("/new.txt"),new java.io.ByteArrayInputStream(new byte[]{1}),1)).httpStatus());
  mounts.restoreRecycled(user,"trash-drive",row.id());assertEquals("notes.txt",mounts.list(user,"trash-drive",DrivePath.of("/")).getFirst().name());
 }
 @Test void restorationConflictAndPeerCannotTouchOtherUsersVault()throws Exception{
  var row=mounts.recycle(user,"trash-drive",DrivePath.of("/notes.txt"));Files.writeString(privateRoot.resolve("notes.txt"),"new");
  assertEquals(409,assertThrows(ApplicationFault.class,()->mounts.restoreRecycled(user,"trash-drive",row.id())).httpStatus());
  assertEquals("TRASHED",mounts.recycleBin(user,"trash-drive").getFirst().status());
  assertTrue(mounts.recycleBin(peer,"trash-drive").isEmpty());assertEquals(404,assertThrows(ApplicationFault.class,()->mounts.purgeRecycled(peer,"trash-drive",row.id())).httpStatus());
  mounts.purgeRecycled(user,"trash-drive",row.id());assertEquals("new",Files.readString(privateRoot.resolve("notes.txt")));
 }
 @Test void retentionOnlyPurgesExpiredRecordedVaultItems()throws Exception{
  var row=mounts.recycle(user,"trash-drive",DrivePath.of("/notes.txt"));Files.writeString(privateRoot.resolve("keep.txt"),"keep");
  jdbc.update("UPDATE sc_drive_trash SET expires_at=1 WHERE id=?",row.id());assertEquals(1,mounts.cleanExpiredRecycle());
  assertEquals("keep",Files.readString(privateRoot.resolve("keep.txt")));assertTrue(mounts.recycleBin(user,"trash-drive").isEmpty());
 }
 @Test void authorizationFingerprintSurvivesPublicationButChangesWithPermissionGeneration(){
  String before=mounts.uploadAuthorization(user,"trash-drive",DrivePath.of("/notes.txt"),8);
  assertEquals(before,mounts.uploadAuthorization(user,"trash-drive",DrivePath.of("/notes.txt"),8));
  jdbc.update("UPDATE sc_drive_grant SET revision=revision+1 WHERE user_id=3");assertNotEquals(before,mounts.uploadAuthorization(user,"trash-drive",DrivePath.of("/notes.txt"),8));
 }
 @Test void databaseFailureAfterRestoreLeavesAnExplicitUncertaintyBarrier()throws Exception{
  var row=mounts.recycle(user,"trash-drive",DrivePath.of("/notes.txt"));failStatus="RESTORED";
  assertThrows(ApplicationFault.class,()->mounts.restoreRecycled(user,"trash-drive",row.id()));
  assertEquals("original",Files.readString(privateRoot.resolve("notes.txt")));
  assertEquals("UNCERTAIN",mounts.recycleBin(user,"trash-drive").getFirst().status());
  assertThrows(ApplicationFault.class,()->mounts.restoreRecycled(user,"trash-drive",row.id()));
 }
 @Test void databaseFailureAfterPurgeCannotMakeADeletedItemLookRetryable(){
  var row=mounts.recycle(user,"trash-drive",DrivePath.of("/notes.txt"));failStatus="PURGED";
  assertThrows(ApplicationFault.class,()->mounts.purgeRecycled(user,"trash-drive",row.id()));
  assertEquals("UNCERTAIN",mounts.recycleBin(user,"trash-drive").getFirst().status());
 }
}
