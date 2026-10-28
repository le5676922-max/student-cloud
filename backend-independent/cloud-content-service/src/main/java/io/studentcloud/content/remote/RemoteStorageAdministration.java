package io.studentcloud.content.remote;
import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Candidate validation precedes the short configuration commit. Secrets never leave this adapter. */
public final class RemoteStorageAdministration implements RemoteConnections {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final AccountDirectory accounts;
 private final WorkspaceDirectory workspaces;private final ObjectStoreFactory factory;private final RemoteCredentialCipher cipher;private final RemoteEndpointPolicy endpoints;
 private final java.util.concurrent.Semaphore probes=new java.util.concurrent.Semaphore(2);
 private static final String GRANT="list,read,mkdir,upload";
 private record Candidate(String type,ObjectStore.Connection connection,String prefix,RemoteCredentialCipher.Secrets secrets){}
 public RemoteStorageAdministration(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,WorkspaceDirectory workspaces,ObjectStoreFactory factory,String master,String allowed){
  this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setTimeout(15);this.clock=clock;this.accounts=accounts;this.workspaces=workspaces;this.factory=factory;cipher=new RemoteCredentialCipher(master);endpoints=new RemoteEndpointPolicy(allowed);
 }
 public List<Map<String,Object>> types(){return List.of(Map.of("key","aliyun","description","阿里云 OSS"),Map.of("key","s3","description","S3 兼容对象存储"),Map.of("key","minio","description","MinIO / S3"));}
 public List<Map<String,Object>> params(Actor supplied,String type){actor(supplied);provider(type);var list=new ArrayList<Map<String,Object>>();
  for(String[] f:new String[][]{{"endPoint","API Endpoint"},{"bucketName","Bucket 名称"},{"region","区域 Region"},{"accessKey","Access Key ID（编辑留空保留）"},{"secretKey","Secret Key（编辑留空保留）"},{"sessionToken","临时凭据 Token（可选）"},{"basePath","专用目录前缀（选填）"}})
   list.add(Map.of("key",f[0],"name",f[1],"type","input","required",Set.of("endPoint","bucketName","region").contains(f[0]),"defaultValue","","hidden",false));
  list.add(Map.of("key","pathStyleAccess","name","使用 Path Style（MinIO 通常开启）","type","switch","required",false,"defaultValue",!"aliyun".equals(type),"hidden",false));return list;
 }
 public boolean isRemote(long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sc_remote_storage WHERE mount_id=?",Boolean.class,id));}
 @Override public Settings load(long id){
  var rows=jdbc.queryForList("SELECT * FROM sc_remote_storage WHERE mount_id=?",id);if(rows.size()!=1)throw fault(404,"STORAGE_NOT_FOUND","远程存储不存在");var r=rows.getFirst();
  var secret=cipher.decrypt(id,(String)r.get("credentials_ciphertext"));var provider=ObjectStore.Provider.valueOf((String)r.get("provider"));
  var endpoint=endpoints.validate((String)r.get("endpoint"),"https",provider);
  return new Settings(id,new ObjectStore.Connection(provider,endpoint,(String)r.get("bucket_name"),(String)r.get("region"),yes(r.get("path_style")),secret.accessKey(),secret.secretKey(),secret.sessionToken()),(String)r.get("base_prefix"),((Number)r.get("config_version")).longValue(),yes(r.get("write_verified")));
 }
 public List<Map<String,Object>> list(Actor supplied){Actor a=actor(supplied);var ids=a.role()==Role.ADMIN?jdbc.queryForList("SELECT r.mount_id FROM sc_remote_storage r ORDER BY r.mount_id LIMIT 201",Long.class):jdbc.queryForList("SELECT r.mount_id FROM sc_remote_storage r JOIN sc_drive_mount m ON m.id=r.mount_id WHERE m.workspace_id=? ORDER BY r.mount_id LIMIT 201",Long.class,a.workspaceId());if(ids.size()>200)throw fault(409,"STORAGE_BUDGET","存储源超过当前预算");return ids.stream().map(id->dto(a,id,false)).toList();}
 public Map<String,Object> detail(Actor supplied,long id){return dto(actor(supplied),id,true);}
 private Map<String,Object> dto(Actor a,long id,boolean detail){var m=mount(a,id);var r=jdbc.queryForMap("SELECT * FROM sc_remote_storage WHERE mount_id=?",id);var out=new LinkedHashMap<String,Object>();
  out.put("id",id);out.put("key",m.get("mount_key"));out.put("name",m.get("name"));out.put("tenantId",m.get("workspace_id"));out.put("enable",yes(m.get("enabled")));out.put("version",r.get("config_version"));out.put("revision",r.get("config_version"));out.put("mountRevision",m.get("revision"));
  out.put("type",Map.of("key",r.get("storage_type"),"description","ALIYUN_OSS".equals(r.get("provider"))?"阿里云 OSS":"S3 对象存储"));out.put("enableCache",false);out.put("autoRefreshCache",false);out.put("cacheAvailable",false);out.put("writeVerified",yes(r.get("write_verified")));out.put("validatedAt",r.get("validated_at"));out.put("orderNum",jdbc.queryForObject("SELECT COALESCE(MAX(order_num),0) FROM sc_storage_admin_metadata WHERE mount_id=?",Integer.class,id));
  if(detail){var p=new LinkedHashMap<String,Object>();p.put("endPoint",r.get("endpoint"));p.put("bucketName",r.get("bucket_name"));p.put("region",r.get("region"));p.put("basePath",r.get("base_prefix"));p.put("pathStyleAccess",yes(r.get("path_style")));p.put("accessKey","");p.put("secretKey","");p.put("sessionToken","");out.put("storageSourceAllParam",p);out.put("credentialsConfigured",true);}
  return out;
 }
 public Map<String,Object> testConnection(Actor supplied,Map<String,Object> input){Actor a=actor(supplied);Long id=num(input.get("id"));var previous=id==null?null:mount(a,id);Candidate candidate=candidate(input,id);if(id!=null)validateManagedLocation(a,previous,load(id),candidate);return probe(candidate,yes(input.get("verifyWrite")));}
 public long save(Actor supplied,Map<String,Object> input){
  Actor a=actor(supplied);cipher.requireReady();if(input==null)throw invalid();Long id=num(input.get("id"));Map<String,Object> previous=id==null?null:mount(a,id);
  long workspace=workspace(a,input,previous);List<Actor> admins=workspaces.administrators(workspace);
  if(admins.isEmpty())throw fault(409,"STORAGE_ADMIN_REQUIRED","请先为工作空间设置普通管理员");
  Candidate c=candidate(input,id);String name=text(input.get("name"),120);String key=string(input.get("key"));if(key.isBlank())key=id==null?"remote-"+UUID.randomUUID().toString().substring(0,8):(String)previous.get("mount_key");validKey(key);
  boolean enabled=!Boolean.FALSE.equals(input.get("enable"));boolean write=yes(input.get("verifyWrite"));long expected=id==null?0:version(input);
  Long expectedMount=id==null?null:num(input.get("mountRevision"));
  if(id!=null&&(expectedMount==null||expectedMount<1||expectedMount.longValue()!=((Number)previous.get("revision")).longValue()))throw changed();
  if(id!=null&&load(id).version()!=expected)throw changed();
  if(id!=null)validateManagedLocation(a,previous,load(id),c);
  if(id!=null&&yes(previous.get("enabled"))&&connectionRootChanged(load(id),c))throw fault(409,"STORAGE_DISABLE_BEFORE_ROOT_CHANGE","更换 Bucket、端点或目录前缀前，请先停用存储源");
  if(enabled){Map<String,Object> test=probe(c,write);if(!Boolean.TRUE.equals(test.get("passed")))throw fault(409,"STORAGE_PROBE_FAILED",String.valueOf(test.get("message")));}
  Actor fresh=actor(a);if(!Objects.equals(fresh.workspaceId(),a.workspaceId())||fresh.role()!=a.role())throw changed();
  String finalKey=key;
  try{return tx.execute(status->{jdbc.queryForObject("SELECT id FROM sc_content_settings WHERE id=1 FOR UPDATE",Integer.class);
   if(id!=null){
    var locked=jdbc.queryForMap("SELECT * FROM sc_drive_mount WHERE id=? FOR UPDATE",id);
    if(((Number)locked.get("revision")).longValue()!=expectedMount)throw changed();
    if(a.role()!=Role.ADMIN&&!Objects.equals(((Number)locked.get("workspace_id")).longValue(),a.workspaceId()))throw forbidden();
    var guard=jdbc.queryForList("SELECT active_operation FROM sc_remote_write_guard WHERE mount_id=? FOR UPDATE",String.class,id);
    if(!guard.isEmpty()&&guard.getFirst()!=null)throw fault(409,"REMOTE_WRITE_PENDING","有正在执行或结果待核查的写入，不能修改连接");
    if(yes(locked.get("enabled"))&&connectionRootChanged(load(id),c))throw fault(409,"STORAGE_DISABLE_BEFORE_ROOT_CHANGE","更换 Bucket、端点或目录前缀前，请先停用存储源");
   }
   var overlaps=jdbc.queryForList("SELECT mount_id,base_prefix FROM sc_remote_storage WHERE endpoint=? AND bucket_name=?",c.connection().endpoint().toString(),c.connection().bucket());
   for(var row:overlaps)if(!Objects.equals(((Number)row.get("mount_id")).longValue(),id)){
    String existing=(String)row.get("base_prefix");if(c.prefix().startsWith(existing)||existing.startsWith(c.prefix()))throw fault(409,"STORAGE_ROOT_OVERLAP","同一 Bucket 的存储前缀不能重叠，请为每个云盘分配独立前缀");}
   long mountId;
   if(id==null){var generated=new GeneratedKeyHolder();jdbc.update(connection->{var st=connection.prepareStatement("INSERT INTO sc_drive_mount(mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES(?,?,?,?,?,'remote',?,1,?)",java.sql.Statement.RETURN_GENERATED_KEYS);st.setString(1,finalKey);st.setString(2,name);st.setLong(3,workspace);st.setLong(4,a.id());st.setString(5,c.connection().provider().name());st.setBoolean(6,enabled);st.setLong(7,clock.millis());return st;},generated);mountId=Objects.requireNonNull(generated.getKey()).longValue();
    jdbc.update("INSERT INTO sc_remote_storage(mount_id,storage_type,provider,endpoint,bucket_name,region,base_prefix,path_style,credentials_ciphertext,config_version,write_verified,validated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)",mountId,c.type(),c.connection().provider().name(),c.connection().endpoint().toString(),c.connection().bucket(),c.connection().region(),c.prefix(),c.connection().pathStyle(),cipher.encrypt(mountId,c.secrets()),enabled&&write,enabled?clock.millis():null);
    jdbc.update("INSERT INTO sc_storage_admin_metadata(mount_id) VALUES(?)",mountId);
   }else{mountId=id;
    if(jdbc.update("UPDATE sc_remote_storage SET storage_type=?,provider=?,endpoint=?,bucket_name=?,region=?,base_prefix=?,path_style=?,credentials_ciphertext=?,config_version=config_version+1,write_verified=?,validated_at=? WHERE mount_id=? AND config_version=?",c.type(),c.connection().provider().name(),c.connection().endpoint().toString(),c.connection().bucket(),c.connection().region(),c.prefix(),c.connection().pathStyle(),cipher.encrypt(id,c.secrets()),enabled&&write,enabled?clock.millis():null,id,expected)!=1)throw changed();
    jdbc.update("UPDATE sc_drive_mount SET name=?,mount_key=?,engine=?,enabled=?,revision=revision+1 WHERE id=?",name,finalKey,c.connection().provider().name(),enabled,id);
   }
   for(Actor admin:admins){if(admin.role()!=Role.TENANT_ADMIN||!Objects.equals(admin.workspaceId(),workspace))throw changed();jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(?,?,?,?,TRUE,1,?) ON DUPLICATE KEY UPDATE permissions=VALUES(permissions),revision=revision+1",admin.id(),mountId,"/admins/u"+admin.id(),enabled&&write?GRANT:"list,read",clock.millis());}
   return mountId;
  });}catch(org.springframework.dao.DuplicateKeyException e){throw fault(409,"STORAGE_KEY_CONFLICT","存储别名已存在，请使用其他别名");}
 }
 public void setEnabled(Actor supplied,long id,boolean enabled){Actor a=actor(supplied);mount(a,id);if(!enabled){jdbc.update("UPDATE sc_drive_mount SET enabled=FALSE,revision=revision+1 WHERE id=?",id);return;}var dto=detail(a,id);var input=new LinkedHashMap<>(dto);input.put("type",((Map<?,?>)dto.get("type")).get("key"));input.put("enable",true);input.put("verifyWrite",dto.get("writeVerified"));save(a,input);}
 public void delete(Actor supplied,long id,boolean confirmed){Actor a=actor(supplied);mount(a,id);throw fault(409,"STORAGE_HISTORY_RETAINED","远程连接请先停用保留；当前不自动删除连接历史或任何远端对象");}
 private Map<String,Object> probe(Candidate c,boolean write){noTransaction();if(!probes.tryAcquire())throw fault(429,"STORAGE_PROBE_BUSY","正在测试其他连接，请稍后重试");try{return performProbe(c,write);}finally{probes.release();}}
 private Map<String,Object> performProbe(Candidate c,boolean write){try(ObjectStore store=factory.open(c.connection())){
  store.list(c.prefix(),"/",null,1);if(!write)return Map.of("passed",true,"cleanup",true,"writeVerified",false,"message","只读连接成功；未验证上传权限。");
  store.requireCreateOnly();String nonce=UUID.randomUUID().toString(),key=c.prefix()+".studentcloud-probes/"+nonce;byte[] bytes=new byte[64];new java.security.SecureRandom().nextBytes(bytes);ObjectStore.Receipt receipt=null;RuntimeException error=null;boolean cleanup=true;
  try{receipt=store.createOnly(key,new ByteArrayInputStream(bytes),bytes.length,nonce);
   boolean rejected=false;try{store.createOnly(key,new ByteArrayInputStream(new byte[]{1}),1,nonce);}catch(ObjectStore.WriteFailure e){if(e.httpStatus()==409&&!e.mayHavePublished())rejected=true;else throw e;}catch(ApplicationFault e){if(e.httpStatus()==409)rejected=true;else throw e;}
   if(!rejected)throw fault(409,"STORAGE_OVERWRITE_UNSAFE","服务未拒绝同名覆盖，不能启用写入");
   try(var read=store.read(key)){if(!Arrays.equals(bytes,read.input().readNBytes(65)))throw fault(409,"STORAGE_VERIFY_FAILED","测试对象回读校验失败");}
  }catch(IOException e){error=fault(502,"STORAGE_VERIFY_FAILED","测试对象读取未完成");}catch(RuntimeException e){error=e;}
  finally{if(receipt!=null)try{store.cleanupProbe(receipt);}catch(RuntimeException failed){cleanup=false;}}
  if(receipt==null&&error instanceof ObjectStore.WriteFailure uncertain&&uncertain.mayHavePublished())cleanup=false;
  if(!cleanup)return Map.of("passed",false,"cleanup",false,"writeVerified",false,"message","临时测试对象未确认清理，请勿重复测试；请核查系统探针前缀。未保存配置。","probeKey",key);if(error!=null)throw safe(error);
  return Map.of("passed",true,"cleanup",true,"writeVerified",true,"message","列目录、条件写入、同名拒绝、回读和探针清理全部通过。");
 }catch(RuntimeException e){throw safe(e);}}
 private Candidate candidate(Map<String,Object> input,Long id){Map<?,?> p=input.get("storageSourceAllParam") instanceof Map<?,?> m?m:Map.of();String type=string(input.get("type"));ObjectStore.Provider provider=provider(type);Settings old=id==null?null:load(id);
  String ak=string(p.get("accessKey")),sk=string(p.get("secretKey")),token=string(p.get("sessionToken"));if(old!=null){if(ak.isBlank())ak=old.connection().accessKey();if(sk.isBlank()){if(!ak.equals(old.connection().accessKey()))throw invalid();sk=old.connection().secretKey();}if(token.isBlank())token=old.connection().sessionToken();}
  if(ak.isBlank()||sk.isBlank()||ak.length()>512||sk.length()>4096||token.length()>16384)throw fault(400,"STORAGE_CREDENTIALS_REQUIRED","请填写完整的访问密钥，新建时不能留空");
  String prefix=string(p.get("basePath"));if(prefix.startsWith("/"))prefix=prefix.substring(1);if(!prefix.isEmpty()&&!prefix.endsWith("/"))prefix+="/";
  if(prefix.length()>500||prefix.contains("\\")||prefix.contains("//")||prefix.codePoints().anyMatch(Character::isISOControl))throw invalid();for(String part:prefix.split("/"))if(part.equals(".")||part.equals("..")||part.startsWith(".studentcloud-"))throw invalid();
  String bucket=text(p.get("bucketName"),63),region=text(p.get("region"),64);if(!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")||bucket.contains("..")||!region.matches("[a-z0-9-]{1,64}"))throw invalid();
  var endpoint=endpoints.validate(string(p.get("endPoint")),string(p.get("endPointScheme")),provider);
  var secret=new RemoteCredentialCipher.Secrets(ak,sk,token);return new Candidate(type,new ObjectStore.Connection(provider,endpoint,bucket,region,yes(p.get("pathStyleAccess")),ak,sk,token),prefix,secret);
 }
 private static boolean connectionRootChanged(Settings old,Candidate next){var a=old.connection();var b=next.connection();return a.provider()!=b.provider()||!a.endpoint().equals(b.endpoint())||!a.bucket().equals(b.bucket())||!a.region().equals(b.region())||a.pathStyle()!=b.pathStyle()||!old.basePrefix().equals(next.prefix());}
 private static void validateManagedLocation(Actor actor,Map<String,Object> mount,Settings old,Candidate next){if(actor.role()!=Role.ADMIN&&((Number)mount.get("owner_id")).longValue()!=actor.id()&&connectionRootChanged(old,next))throw fault(403,"STORAGE_LOCATION_OWNER_REQUIRED","此连接由其他管理员分配，Bucket、端点和专用前缀须由创建者或全局管理员修改");}
 private Map<String,Object> mount(Actor a,long id){var rows=jdbc.queryForList("SELECT * FROM sc_drive_mount WHERE id=? AND engine IN('ALIYUN_OSS','S3')",id);if(rows.size()!=1)throw fault(404,"STORAGE_NOT_FOUND","存储源不存在");var m=rows.getFirst();if(a.role()!=Role.ADMIN&&!Objects.equals(((Number)m.get("workspace_id")).longValue(),a.workspaceId()))throw forbidden();return m;}
 private Actor actor(Actor a){Actor now=accounts.current(a);if(now.role()!=Role.ADMIN&&now.role()!=Role.TENANT_ADMIN)throw forbidden();return now;}
 private long workspace(Actor a,Map<String,Object> in,Map<String,Object> previous){Long id=num(in.get("tenantId"));if(a.role()==Role.TENANT_ADMIN){if(id!=null&&!id.equals(a.workspaceId()))throw forbidden();id=a.workspaceId();}if(previous!=null){long prior=((Number)previous.get("workspace_id")).longValue();if(id==null)id=prior;if(id!=prior)throw forbidden();}if(id==null||!accounts.workspaceEnabled(id))throw fault(400,"STORAGE_WORKSPACE_REQUIRED","请选择已启用且有普通管理员的工作空间");return id;}
 private static ObjectStore.Provider provider(String type){return switch(type){case "aliyun"->ObjectStore.Provider.ALIYUN_OSS;case "s3","minio"->ObjectStore.Provider.S3;default->throw fault(501,"STORAGE_TYPE_UNAVAILABLE","当前只开放 OSS、S3 和 MinIO 远程存储");};}
 private static long version(Map<String,Object> in){Long value=num(in.containsKey("version")?in.get("version"):in.get("revision"));if(value==null||value<1)throw changed();return value;}
 private static Long num(Object o){if(o==null||"".equals(o))return null;try{return Long.valueOf(o.toString());}catch(Exception e){throw invalid();}}
 private static String string(Object o){return o==null?"":o.toString().trim();}
 private static boolean yes(Object o){return Boolean.TRUE.equals(o)||"true".equalsIgnoreCase(String.valueOf(o));}
 private static String text(Object o,int max){String v=string(o);if(v.isBlank()||v.length()>max||v.codePoints().anyMatch(Character::isISOControl))throw invalid();return v;}
 private static void validKey(String key){if(!key.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid();}
 private static void noTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw fault(409,"STORAGE_TRANSACTION_BOUNDARY","不能在数据库事务内执行远程连接测试");}
 private static ApplicationFault safe(RuntimeException e){if(e instanceof ApplicationFault a)return a;if(e instanceof ObjectStore.WriteFailure w)return fault(w.httpStatus(),w.publicCode(),w.getMessage());return fault(503,"STORAGE_UNAVAILABLE","远程存储操作未完成，请核对连接状态");}
 private static ApplicationFault invalid(){return fault(400,"STORAGE_INVALID","存储连接参数、路径或版本无效");}private static ApplicationFault forbidden(){return fault(403,"STORAGE_FORBIDDEN","没有此工作空间的存储管理权限");}private static ApplicationFault changed(){return fault(409,"STORAGE_CHANGED","配置已变化，请重新读取后保存");}private static ApplicationFault fault(int s,String c,String m){return new ApplicationFault(c,s,m);}
}
