package io.studentcloud.content.remote;
import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import io.studentcloud.drive.application.*;
import io.studentcloud.drive.object.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** All keys are server-derived from a fresh mount grant; provider IO never holds an SQL transaction. */
public final class RemoteDriveService implements DriveOperations {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final AccountDirectory accounts;
 private final RemoteConnections configs;private final ObjectStoreFactory factory;private final Path stage;private final Semaphore io=new Semaphore(4);
 private static final Set<String> KNOWN=Set.of("list","read","mkdir","upload");
 private record Access(Actor actor,long mountId,long mountRevision,long grantRevision,String root,String prefix,Set<String> permissions,Long quota,RemoteConnections.Settings config){
  String stamp(){return actor.id()+"|"+actor.role()+"|"+actor.workspaceId()+"|"+mountId+"|"+mountRevision+"|"+grantRevision+"|"+root+"|"+permissions.stream().sorted().toList()+"|"+quota+"|"+config.version();}
 }
 private record Management(Actor actor,long mountId,long revision,Long workspaceId){}
 public RemoteDriveService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,RemoteConnections configs,ObjectStoreFactory factory,Path staging){this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setTimeout(10);this.clock=clock;this.accounts=accounts;this.configs=configs;this.factory=factory;stage=staging==null?null:staging.toAbsolutePath().normalize();}
 public List<MountView> listMounts(Actor supplied){
  Actor actor=accounts.current(supplied);boolean global=actor.role()==Role.ADMIN;
  String fixedRoot=(actor.role()==Role.TENANT_ADMIN?"/admins/u":"/members/u")+actor.id();
  var rows=jdbc.queryForList("SELECT m.id,m.mount_key,m.name,m.workspace_id,m.owner_id,m.engine,m.enabled,m.revision,r.write_verified,g.root_path grant_root,g.permissions grant_permissions "
    +"FROM sc_drive_mount m JOIN sc_remote_storage r ON r.mount_id=m.id LEFT JOIN sc_drive_grant g ON g.mount_id=m.id AND g.user_id=? "
    +"WHERE m.engine IN('S3','ALIYUN_OSS') AND (?=TRUE OR (m.enabled=TRUE AND m.revision>=1 AND m.workspace_id=? AND g.enabled=TRUE AND g.revision>=1 AND g.root_path=? AND (g.quota_bytes IS NULL OR g.quota_bytes>=0))) ORDER BY m.id LIMIT 201",
    actor.id(),global,actor.workspaceId(),fixedRoot);
  if(rows.size()>200)throw budget();var result=new ArrayList<MountView>();
  for(var row:rows){
   if(!global&&!fixedRoot.equals(row.get("grant_root")))continue;
   Set<String> permissions=new HashSet<>();
   if(global)permissions.addAll(KNOWN);
   else if(row.get("grant_permissions") instanceof String csv&&csv.length()<=1024){permissions.addAll(Arrays.asList(csv.split(",")));permissions.retainAll(KNOWN);}
   if(!yes(row.get("write_verified")))permissions.retainAll(Set.of("list","read"));
   if(!yes(row.get("enabled")))permissions.clear();
   result.add(view(row,Set.copyOf(permissions)));
  }
  return List.copyOf(result);
 }
 private MountView view(Map<String,Object> r,Set<String> p){return new MountView(number(r,"id"),(String)r.get("mount_key"),(String)r.get("name"),number(r,"workspace_id"),number(r,"owner_id"),(String)r.get("engine"),yes(r.get("enabled")),number(r,"revision"),p);}
 public Set<String> directoryCapabilities(Actor a,String key,DrivePath path){Access x=access(a,key,Set.of("list"));try(ObjectStore store=open(x)){directory(store,x,path);ensureUnchanged(x,key);return x.permissions();}}
 public List<FileRecord> list(Actor a,String key,DrivePath path,boolean refresh){Access x=access(a,key,Set.of("list"));try(ObjectStore store=open(x)){directory(store,x,path);String prefix=object(x,path,true);var found=new LinkedHashMap<String,FileRecord>();pages(store,prefix,"/",page->{for(var o:page.objects()){if(o.key().equals(prefix))continue;entry(found,x,path,prefix,o.key(),false,o.size(),o.modifiedAt());}for(String p:page.prefixes())entry(found,x,path,prefix,p,true,0,Instant.EPOCH);});ensureUnchanged(x,key);return found.values().stream().sorted(Comparator.comparing(FileRecord::directory).reversed().thenComparing(FileRecord::name)).toList();}}
 private void entry(Map<String,FileRecord> out,Access x,DrivePath path,String prefix,String key,boolean folder,long size,Instant time){if(!key.startsWith(prefix))throw invalidResponse();String name=key.substring(prefix.length());if(name.endsWith("/")){folder=true;name=name.substring(0,name.length()-1);}if(name.isEmpty()||name.contains("/")||name.toLowerCase(Locale.ROOT).startsWith(".studentcloud-"))return;DrivePath child=path.child(name);out.put(name,new FileRecord(child,name,folder,size,time==null?Instant.EPOCH:time));if(out.size()>10000)throw budget();}
 public Optional<FileRecord> stat(Actor a,String key,DrivePath path){Access x=access(a,key,Set.of("list"));try(ObjectStore store=open(x)){var result=stat(store,x,path);ensureUnchanged(x,key);return result;}}
 private Optional<FileRecord> stat(ObjectStore s,Access x,DrivePath path){if(path.isRoot())return Optional.of(dir(path));String key=object(x,path,false);var file=s.head(key);if(file.isPresent())return Optional.of(record(path,file.get()));var marker=s.head(key+"/");if(marker.isPresent())return Optional.of(dir(path));var page=s.list(key+"/","/",null,1);return !page.objects().isEmpty()||!page.prefixes().isEmpty()?Optional.of(dir(path)):Optional.empty();}
 private void directory(ObjectStore store,Access x,DrivePath path){if(!stat(store,x,path).map(FileRecord::directory).orElse(false))throw fault(404,"DRIVE_NOT_FOUND","远程目录不存在");}
 public void prepareUpload(Actor a,String key,DrivePath path,long size){checkSize(size);Access x=access(a,key,Set.of("upload"));try(ObjectStore store=open(x)){directory(store,x,path.parent());if(stat(store,x,path).isPresent())throw conflict();checkQuota(store,x,size);ensureUnchanged(x,key);}}
 public String uploadAuthorization(Actor a,String key,DrivePath path,long size){checkSize(size);Access x=access(a,key,Set.of("upload"));try(ObjectStore store=open(x)){directory(store,x,path.parent());ensureUnchanged(x,key);return hash(x.stamp()+"|"+path.parent().value());}}
 public FileRecord mkdir(Actor a,String key,DrivePath path){if(path.isRoot())throw invalid();return write(a,key,path,new ByteArrayInputStream(new byte[0]),0,true);}
 public FileRecord upload(Actor a,String key,DrivePath path,InputStream input,long size){checkSize(size);if(path.isRoot())throw invalid();return write(a,key,path,input,size,false);}
 private FileRecord write(Actor supplied,String key,DrivePath path,InputStream input,long size,boolean folder){
  noTransaction();Access before=access(supplied,key,Set.of(folder?"mkdir":"upload"));if(!io.tryAcquire())throw fault(429,"REMOTE_BUSY","远程存储正在处理其他读写，请稍后重试");Path temporary=null;String operation=null;boolean publishing=false;
  try{
   if(stage==null||!Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(stage))throw fault(503,"REMOTE_STAGING_MISSING","尚未配置独立上传暂存目录");
   temporary=Files.createTempFile(stage,"remote-write-",".tmp");try(OutputStream out=Files.newOutputStream(temporary,StandardOpenOption.WRITE)){copyExact(input,out,size);}
   Access current=access(supplied,key,Set.of(folder?"mkdir":"upload"));if(!current.stamp().equals(before.stamp()))throw changed();
   String id=UUID.randomUUID().toString();operation=tx.execute(status->{
    jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,current.mountId());
    jdbc.update("INSERT INTO sc_remote_write_guard(mount_id,active_operation) VALUES(?,NULL) ON DUPLICATE KEY UPDATE mount_id=mount_id",current.mountId());
    var active=jdbc.queryForList("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=? FOR UPDATE",String.class,current.mountId());
    if(active.getFirst()!=null)throw fault(409,"REMOTE_WRITE_PENDING","此存储源有正在执行或结果待核查的写入，请先核对任务");
    verifySql(current);
    jdbc.update("INSERT INTO sc_remote_write_operation(id,mount_id,actor_id,grant_root,target_path,object_key,is_directory,config_version,authorization_hash,status,reserved_bytes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?)",id,current.mountId(),current.actor().id(),current.root(),path.value(),object(current,path,folder),folder,current.config().version(),hash(current.stamp()),size,clock.millis(),clock.millis());
    jdbc.update("UPDATE sc_remote_write_guard SET active_operation=? WHERE mount_id=?",id,current.mountId());return id;
   });
   ObjectStore store=open(current);try{
    directory(store,current,path.parent());if(stat(store,current,path).isPresent())throw conflict();checkQuota(store,current,size);store.requireCreateOnly();ensureUnchanged(current,key);
    String finalOperation=operation;tx.executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,current.mountId());verifySql(current);jdbc.update("UPDATE sc_remote_write_operation SET status='PUBLISHING',updated_at=? WHERE id=? AND status='PREPARING'",clock.millis(),finalOperation);});publishing=true;
    ObjectStore.Receipt receipt;try(InputStream data=Files.newInputStream(temporary,LinkOption.NOFOLLOW_LINKS)){receipt=store.createOnly(object(current,path,folder),data,size,id);}
    if(receipt==null||!object(current,path,folder).equals(receipt.key())||receipt.size()!=size)throw invalidResponse();
    ensureUnchanged(current,key);finish(operation,current.mountId(),"COMPLETED",true);
    return folder?dir(path):new FileRecord(path,path.name(),false,size,clock.instant());
   }finally{closeQuietly(store);}
  }catch(ObjectStore.WriteFailure e){boolean uncertain=publishing&&e.mayHavePublished();if(operation!=null)finish(operation,before.mountId(),uncertain?"UNCERTAIN":"FAILED",!uncertain);throw fault(e.httpStatus(),e.publicCode(),e.getMessage());}
  catch(RuntimeException|IOException e){if(operation!=null)finish(operation,before.mountId(),publishing?"UNCERTAIN":"FAILED",!publishing);if(e instanceof ApplicationFault a)throw a;throw fault(503,"REMOTE_WRITE_UNCONFIRMED",publishing?"写入结果未确认，请检查目标文件和任务状态，不要重复提交":"远程写入未完成，请核对连接和文件大小");}
  finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}io.release();}
 }
 public ReadHandle readWithMetadata(Actor a,String key,DrivePath path){noTransaction();Access x=access(a,key,Set.of("read"));if(!io.tryAcquire())throw fault(429,"REMOTE_BUSY","同时读取数量已达上限");ObjectStore store=null;ObjectStore.Read read=null;
  try{store=open(x);String requested=object(x,path,false);read=store.read(requested);if(!requested.equals(read.item().key()))throw invalidResponse();if(read.item().size()>MountService.MAX_TRANSFER_BYTES||read.item().size()<0)throw fault(413,"REMOTE_FILE_SIZE","单文件读取上限50MiB");ensureUnchanged(x,key);ObjectStore owned=store;ObjectStore.Read content=read;long expected=read.item().size();
   InputStream guarded=new FilterInputStream(content.input()){
    long bytes;boolean closed;
    public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
    public int read(byte[] b,int off,int len)throws IOException{
     int n=super.read(b,off,len);
     if(n>0&&(bytes+=n)>expected)throw new IOException("远程文件内容超过已核验长度");
     if(n<0&&bytes!=expected)throw new IOException("远程文件内容少于已核验长度");
     return n;
    }
    public long skip(long amount)throws IOException{
     if(amount<=0)return 0;long skipped=0;byte[] buffer=new byte[8192];
     while(skipped<amount){int count=read(buffer,0,(int)Math.min(buffer.length,amount-skipped));if(count<0)break;skipped+=count;}
     return skipped;
    }
    public boolean markSupported(){return false;}
    public synchronized void mark(int readlimit){}
    public synchronized void reset()throws IOException{throw new IOException("Mark/reset not supported");}
    public void close()throws IOException{if(closed)return;closed=true;try{content.close();}finally{try{closeQuietly(owned);}finally{io.release();}}}
   };
   return new ReadHandle(record(path,read.item()),guarded);
  }catch(RuntimeException e){try{closeQuietly(read);closeQuietly(store);}finally{io.release();}throw e;}
 }

 /** Public review DTOs contain grant-relative names only, never bucket keys or provider settings. */
 public List<Map<String,Object>> pendingOperations(Actor supplied,String key){
  Management scope=management(supplied,key);
  return jdbc.query("SELECT id,actor_id,target_path,status,created_at,updated_at FROM sc_remote_write_operation WHERE mount_id=? AND status IN('PREPARING','PUBLISHING','UNCERTAIN') ORDER BY created_at,id LIMIT 200",(row,n)->{
   Map<String,Object> dto=new LinkedHashMap<>();dto.put("id",row.getString("id"));dto.put("actorId",row.getLong("actor_id"));dto.put("path",row.getString("target_path"));dto.put("status",row.getString("status"));dto.put("createdAt",row.getLong("created_at"));dto.put("updatedAt",row.getLong("updated_at"));return dto;
  },scope.mountId());
 }

 /** Reconcile only affirmative provider ownership evidence. Absence never authorizes a retry. */
 public Map<String,Object> inspectPending(Actor supplied,String key,String operationId){
  Management scope=management(supplied,key);
  if(operationId==null||!operationId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid();
  var operations=jdbc.queryForList("SELECT * FROM sc_remote_write_operation WHERE id=? AND mount_id=?",operationId,scope.mountId());
  if(operations.size()!=1)throw fault(404,"REMOTE_OPERATION_NOT_FOUND","待核查写入不存在");
  var operation=operations.getFirst();String status=(String)operation.get("status");
  if("COMPLETED".equals(status))return inspection(operationId,"COMPLETED",true);
  if(!Set.of("UNCERTAIN","PUBLISHING").contains(status))return inspection(operationId,status,false);
  String active=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=?",String.class,scope.mountId());
  RemoteConnections.Settings config=configs.load(scope.mountId());
  if(!operationId.equals(active)||number(operation,"config_version")!=config.version())return inspection(operationId,status,false);
  String objectKey=(String)operation.get("object_key");long expectedBytes=number(operation,"reserved_bytes");
  String root=(String)operation.get("grant_root");DrivePath relative=DrivePath.of((String)operation.get("target_path"));
  String expectedKey=config.basePrefix()+("/".equals(root)?"":DrivePath.of(root).relative()+"/")+relative.relative()+(yes(operation.get("is_directory"))?"/":"");
  if(!expectedKey.equals(objectKey)||expectedBytes<0)return inspection(operationId,status,false);
  if(!io.tryAcquire())throw fault(429,"REMOTE_BUSY","远程存储正在处理其他读写，请稍后核查");
  Optional<ObjectStore.Item> observed;ObjectStore store=null;
  try{noTransaction();store=factory.open(config.connection());observed=store.head(objectKey);}
  catch(RuntimeException failure){throw fault(502,"REMOTE_INSPECTION_FAILED","远程写入暂时无法核实，冻结状态已保留，请稍后重试");}
  finally{try{closeQuietly(store);}finally{io.release();}}
  if(observed.isEmpty()||!objectKey.equals(observed.get().key())||observed.get().size()!=expectedBytes||!operationId.equals(observed.get().operationId()))return inspection(operationId,status,false);
  Management fresh=management(supplied,key);
  if(!scope.equals(fresh))throw changed();
  tx.executeWithoutResult(transaction->{
   var mount=jdbc.queryForMap("SELECT revision,workspace_id FROM sc_drive_mount WHERE id=? FOR UPDATE",scope.mountId());
   if(number(mount,"revision")!=scope.revision()||!Objects.equals(number(mount,"workspace_id"),scope.workspaceId()))throw changed();
   long configVersion=jdbc.queryForObject("SELECT config_version FROM sc_remote_storage WHERE mount_id=?",Long.class,scope.mountId());
   String lockedActive=jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=? FOR UPDATE",String.class,scope.mountId());
   var locked=jdbc.queryForMap("SELECT status,object_key,config_version,reserved_bytes FROM sc_remote_write_operation WHERE id=? AND mount_id=? FOR UPDATE",operationId,scope.mountId());
   if(configVersion!=config.version()||!objectKey.equals(locked.get("object_key"))||number(locked,"config_version")!=config.version())throw changed();
   if("COMPLETED".equals(locked.get("status")))return;
   if(!operationId.equals(lockedActive)||!Set.of("UNCERTAIN","PUBLISHING").contains(locked.get("status"))||number(locked,"reserved_bytes")!=expectedBytes)throw changed();
   if(jdbc.update("UPDATE sc_remote_write_operation SET status='COMPLETED',reserved_bytes=0,updated_at=? WHERE id=? AND status IN('UNCERTAIN','PUBLISHING')",clock.millis(),operationId)!=1)throw changed();
   if(jdbc.update("UPDATE sc_remote_write_guard SET active_operation=NULL WHERE mount_id=? AND active_operation=?",scope.mountId(),operationId)!=1)throw changed();
  });
  return inspection(operationId,"COMPLETED",true);
 }
 private Management management(Actor supplied,String key){
  noTransaction();Actor actor=accounts.current(supplied);
  if(actor.role()!=Role.ADMIN&&actor.role()!=Role.TENANT_ADMIN)throw denied();
  if(key==null||!key.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid();
  var mounts=jdbc.queryForList("SELECT id,workspace_id,revision FROM sc_drive_mount WHERE mount_key=? AND engine IN('S3','ALIYUN_OSS')",key);
  if(mounts.size()!=1)throw denied();var mount=mounts.getFirst();Long workspace=number(mount,"workspace_id");
  if(actor.role()==Role.TENANT_ADMIN&&(workspace==null||!workspace.equals(actor.workspaceId())))throw denied();
  return new Management(actor,number(mount,"id"),number(mount,"revision"),workspace);
 }
 private static Map<String,Object> inspection(String id,String status,boolean confirmed){
  return Map.of("id",id,"status",status,"result",confirmed?"CONFIRMED":"PENDING","message",confirmed?"远端对象已确认属于本次写入，写入冻结已解除。":"尚未取得匹配本次写入的完整证据，冻结状态已保留；请勿重复提交。");
 }
 private Access access(Actor supplied,String key,Set<String> required){noTransaction();Actor a=accounts.current(supplied);if(key==null||!key.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid();var rows=jdbc.queryForList("SELECT * FROM sc_drive_mount WHERE mount_key=? AND engine IN('S3','ALIYUN_OSS')",key);if(rows.size()!=1)throw denied();var m=rows.getFirst();if(!yes(m.get("enabled")))throw denied();long id=number(m,"id");var config=configs.load(id);String root="/";Set<String> permissions=KNOWN;Long quota=null;long grantRevision=0;
  if(a.role()!=Role.ADMIN){if(a.workspaceId()==null||!a.workspaceId().equals(number(m,"workspace_id")))throw denied();var grants=jdbc.queryForList("SELECT * FROM sc_drive_grant WHERE user_id=? AND mount_id=? AND enabled=TRUE",a.id(),id);if(grants.size()!=1)throw denied();var grant=grants.getFirst();root=(String)grant.get("root_path");String fixed=(a.role()==Role.TENANT_ADMIN?"/admins/u":"/members/u")+a.id();if(!fixed.equals(root))throw denied();permissions=new HashSet<>(Arrays.asList(((String)grant.get("permissions")).split(",")));permissions.retainAll(KNOWN);quota=number(grant,"quota_bytes");grantRevision=number(grant,"revision");}
  if(!config.writeVerified())permissions=Set.of("list","read").stream().filter(permissions::contains).collect(java.util.stream.Collectors.toSet());if(!permissions.containsAll(required))throw denied();
  String prefix=config.basePrefix()+(root.equals("/")?"":root.substring(1)+"/");return new Access(a,id,number(m,"revision"),grantRevision,root,prefix,Set.copyOf(permissions),quota,config);
 }
 private void verifySql(Access x){var m=jdbc.queryForMap("SELECT enabled,revision FROM sc_drive_mount WHERE id=?",x.mountId());if(!yes(m.get("enabled"))||number(m,"revision")!=x.mountRevision())throw changed();long v=jdbc.queryForObject("SELECT config_version FROM sc_remote_storage WHERE mount_id=?",Long.class,x.mountId());if(v!=x.config().version())throw changed();if(x.actor().role()!=Role.ADMIN){var g=jdbc.queryForMap("SELECT revision,enabled FROM sc_drive_grant WHERE user_id=? AND mount_id=? FOR UPDATE",x.actor().id(),x.mountId());if(!yes(g.get("enabled"))||number(g,"revision")!=x.grantRevision())throw changed();}}
 private void ensureUnchanged(Access old,String key){if(!access(old.actor(),key,Set.of()).stamp().equals(old.stamp()))throw changed();}
 private void finish(String operation,long mount,String status,boolean release){
  tx.executeWithoutResult(s->{
   jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,mount);
   jdbc.queryForObject("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=? FOR UPDATE",String.class,mount);
   // A later network exception cannot undo an already confirmed publication.
   jdbc.update("UPDATE sc_remote_write_operation SET status=?,reserved_bytes=CASE WHEN ? THEN 0 ELSE reserved_bytes END,updated_at=? WHERE id=? AND mount_id=? AND status IN('PREPARING','PUBLISHING','UNCERTAIN')",status,release,clock.millis(),operation,mount);
   if(release)jdbc.update("UPDATE sc_remote_write_guard SET active_operation=NULL WHERE mount_id=? AND active_operation=?",mount,operation);
  });
 }
 private void checkQuota(ObjectStore store,Access x,long incoming){if(x.quota()==null)return;long[] total={0};pages(store,x.prefix(),null,page->{for(var item:page.objects()){if(!item.key().startsWith(x.prefix())||item.size()<0)throw invalidResponse();total[0]=Math.addExact(total[0],item.size());}});if(incoming>x.quota()-total[0])throw fault(409,"DRIVE_QUOTA_EXCEEDED","个人存储容量不足，请联系空间管理员调整额度");}
 private void pages(ObjectStore store,String prefix,String delimiter,java.util.function.Consumer<ObjectStore.Page> action){String token=null;Set<String> seen=new HashSet<>();int count=0;long start=System.nanoTime();for(int page=0;page<20;page++){var result=store.list(prefix,delimiter,token,1000);count+=result.objects().size()+result.prefixes().size();if(count>10000||System.nanoTime()-start>60_000_000_000L)throw budget();action.accept(result);token=result.nextToken();if(token==null||token.isBlank())return;if(!seen.add(token))throw invalidResponse();}throw budget();}
 private ObjectStore open(Access a){noTransaction();return factory.open(a.config().connection());}
 private static void closeQuietly(AutoCloseable resource){if(resource!=null)try{resource.close();}catch(Exception ignored){}}
 private static String object(Access x,DrivePath path,boolean directory){String relative=path.isRoot()?"":path.value().substring(1);String key=x.prefix()+relative;if(directory&&!key.isEmpty()&&!key.endsWith("/"))key+="/";if(!key.startsWith(x.prefix()))throw denied();return key;}
 private static FileRecord record(DrivePath p,ObjectStore.Item i){return new FileRecord(p,p.name(),false,i.size(),i.modifiedAt()==null?Instant.EPOCH:i.modifiedAt());}private static FileRecord dir(DrivePath p){return new FileRecord(p,p.isRoot()?"/":p.name(),true,0,Instant.EPOCH);}
 private static void copyExact(InputStream in,OutputStream out,long expected)throws IOException{
  long n=0,started=System.nanoTime();int emptyReads=0;byte[] buffer=new byte[16384];
  for(int r;(r=in.read(buffer))!=-1;){
   if(System.nanoTime()-started>=60_000_000_000L)throw fault(408,"REMOTE_STAGING_TIMEOUT","上传暂存超时，请重新检查文件后重试");
   if(r==0){if(++emptyReads>1024)throw invalid();continue;}emptyReads=0;
   if((n+=r)>expected)throw invalid();out.write(buffer,0,r);
  }
  if(n!=expected)throw invalid();
 }
 private static void noTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw fault(409,"REMOTE_TRANSACTION_BOUNDARY","远程读写不能占用数据库事务");}
 private static Long number(Map<String,Object> m,String k){Object v=m.get(k);return v==null?null:((Number)v).longValue();}private static boolean yes(Object v){return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()!=0;}
 private static void checkSize(long s){if(s<1||s>MountService.MAX_TRANSFER_BYTES)throw fault(413,"DRIVE_SIZE","单文件大小须在1字节至50MiB之间");}
 private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static ApplicationFault invalid(){return fault(400,"REMOTE_INVALID","文件路径或长度无效");}private static ApplicationFault invalidResponse(){return fault(502,"REMOTE_RESPONSE_INVALID","远端目录响应无效，请核查服务兼容性");}private static ApplicationFault budget(){return fault(409,"REMOTE_LIST_BUDGET","目录超过当前检索预算，请缩小范围");}private static ApplicationFault conflict(){return fault(409,"DRIVE_CONFLICT","目标已存在，不会覆盖");}private static ApplicationFault changed(){return fault(409,"REMOTE_AUTHORIZATION_CHANGED","配置或授权已变化，请重新检查");}private static ApplicationFault denied(){return fault(403,"DRIVE_FORBIDDEN","无权访问此存储源或执行该操作");}private static ApplicationFault fault(int s,String c,String m){return new ApplicationFault(c,s,m);}
}
