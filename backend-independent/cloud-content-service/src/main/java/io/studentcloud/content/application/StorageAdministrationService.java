package io.studentcloud.content.application;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.account.WorkspaceDirectory;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.LocalFileStore;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.LocalStorageRequestView;
import io.studentcloud.drive.application.WorkspaceCloudService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** HTTP-facing local mount metadata adapter. It never exposes physical roots to workspace actors. */
@Service
public final class StorageAdministrationService {
    private static final Set<String> PERMISSIONS=Set.of("list","read","mkdir","upload","copy","move","rename","delete");
    private static final long MAX_QUOTA_BYTES=102400L*1024*1024*1024;
    private static final List<Map<String,Object>> PERMISSION_OPTIONS=List.of(
            option("list","查看目录"),option("read","读取文件"),option("mkdir","新建文件夹"),option("upload","上传文件"),
            option("copy","复制文件"),option("move","移动文件"),option("rename","重命名"),option("delete","删除文件"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final MountService mounts;
    private final WorkspaceCloudService workspaceCloud;
    private final Path managedRoot;
    private final WorkspaceDirectory workspaces;

    public StorageAdministrationService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,
            MountService mounts,WorkspaceCloudService workspaceCloud,String managedRoot) {
        this(jdbc,manager,clock,accounts,mounts,workspaceCloud,managedRoot,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StorageAdministrationService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,
            MountService mounts,WorkspaceCloudService workspaceCloud,@Value("${student-cloud.storage.managed-root:}") String managedRoot,WorkspaceDirectory workspaces) {
        this.jdbc=Objects.requireNonNull(jdbc);this.transactions=new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(30);this.clock=Objects.requireNonNull(clock);this.accounts=Objects.requireNonNull(accounts);
        this.mounts=Objects.requireNonNull(mounts);this.workspaceCloud=Objects.requireNonNull(workspaceCloud);
        this.managedRoot=managedRoot==null||managedRoot.isBlank()?null:Path.of(managedRoot).toAbsolutePath().normalize();
        this.workspaces=workspaces;
    }

    public record MemberStorageInput(Long storageSourceId,String rootPath,Boolean enable,List<String> permissions,Long quotaBytes) { }
    public record MemberPermissionsInput(List<String> defaultPermissions,List<MemberStorageInput> storages) { }

    public List<Map<String,Object>> supportStorage(Actor supplied){requireAdminOrTenantAdmin(supplied);return List.of(Map.of("key","local","description","服务器本地存储"));}
    public List<Map<String,Object>> storageParams(Actor supplied,String storageType){requireAdminOrTenantAdmin(supplied);if(!"local".equalsIgnoreCase(storageType))throw fault("STORAGE_TYPE_UNAVAILABLE",501,"当前独立后端仅支持服务器本地存储参数");return List.of(Map.of("key","filePath","name","受控目录","type","input","required",true,"defaultValue","","hidden",false));}

    public List<Map<String,Object>> list(Actor supplied){
        Actor actor=requireAdminOrTenantAdmin(supplied);String scope=actor.role()==Role.ADMIN?"":" AND m.workspace_id=? AND EXISTS(SELECT 1 FROM sc_drive_grant g WHERE g.mount_id=m.id AND g.user_id=? AND g.enabled=TRUE)";
        Object[] args=actor.role()==Role.ADMIN?new Object[]{}:new Object[]{actor.workspaceId(),actor.id()};
        List<StorageRow> rows=jdbc.query("SELECT m.id,m.mount_key,m.name,m.workspace_id,m.enabled,COALESCE(a.order_num,0) order_num,a.remark,"
                +"COALESCE(a.default_switch_to_img_mode,FALSE) default_switch_to_img_mode,COALESCE(a.enable_file_operator,TRUE) enable_file_operator,"
                +"COALESCE(a.enable_file_anno_operator,FALSE) enable_file_anno_operator,COALESCE(a.enable_cache,FALSE) enable_cache,"
                +"COALESCE(a.auto_refresh_cache,FALSE) auto_refresh_cache FROM sc_drive_mount m LEFT JOIN sc_storage_admin_metadata a ON a.mount_id=m.id "
                +"WHERE m.engine='LOCAL'"+scope+" ORDER BY COALESCE(a.order_num,0),m.id LIMIT 201",this::storageRow,args);
        if(rows.size()>200)throw fault("STORAGE_BUDGET",409,"存储源数量超过当前管理预算");
        return rows.stream().map(row->rowDto(row,actor.role()==Role.ADMIN)).toList();
    }

    public Map<String,Object> storage(Actor supplied,long id){
        Actor actor=requireAdmin(supplied);StorageRow row=readRow(id);
        Map<String,Object> data=new LinkedHashMap<>(rowDto(row,true));
        data.put("storageSourceAllParam",Map.of("filePath",physicalRoot(id)));
        return data;
    }

    public long save(Actor supplied,Map<String,Object> input){
        Actor actor=requireAdmin(supplied);if(input==null)throw invalid("存储配置无效");
        if(!"local".equalsIgnoreCase(asString(input.get("type"))))throw fault("STORAGE_TYPE_UNAVAILABLE",501,"当前独立后端只保存服务器本地存储");
        String name=text(asString(input.get("name")),120,"存储源名称须为 1 至 120 字符");
        String key=asString(input.get("key"));Long requestedId=number(input.get("id"));
        Long tenantId=number(input.get("tenantId"));if(tenantId!=null&&!accounts.workspaceEnabled(tenantId))throw fault("STORAGE_WORKSPACE_UNAVAILABLE",404,"工作空间不存在或已停用");
        boolean enabled=bool(input.get("enable"),true);String remark=optionalText(asString(input.get("remark")),1000,"备注不能超过 1000 字符");
        List<Actor> administrators=tenantId==null||workspaces==null?List.of():workspaces.administrators(tenantId);
        for(Actor administrator:administrators)if(administrator.role()!=Role.TENANT_ADMIN||!Objects.equals(administrator.workspaceId(),tenantId))throw forbidden();
        Map<?,?> params=input.get("storageSourceAllParam") instanceof Map<?,?> values?values:Map.of();
        if(bool(input.get("enableCache"),false)||bool(input.get("autoRefreshCache"),false))throw fault("STORAGE_CACHE_UNAVAILABLE",501,"当前本地存储未接入缓存能力，不能启用缓存或自动刷新");
        Path target=managedChild(asString(params.get("filePath")));probe(target,true);
        if(requestedId==null){
            if(key==null||key.isBlank())key=slug(name);
            validateKey(key);
            String finalKey=key;
            try{return Objects.requireNonNull(transactions.execute(status->{
                lockAllocator();ensureDisjoint(target,null);requireActorUnchanged(actor);
                MountViewHolder mount=createMount(actor,name,finalKey,target,tenantId);
                if(!enabled)jdbc.update("UPDATE sc_drive_mount SET enabled=FALSE WHERE id=?",mount.id());
                insertMetadata(mount.id(),input,remark);
                provisionAdministrators(mount.id(),target,administrators);
                return mount.id();
            }));}catch(DuplicateKeyException duplicate){throw fault("STORAGE_KEY_CONFLICT",409,"存储别名已存在，请换一个别名");}
        }
        if(requestedId<1)throw invalid("存储源 ID 无效");String updateKey=key==null||key.isBlank()?readKey(requestedId):key;validateKey(updateKey);
        long id=requestedId;
        try{return Objects.requireNonNull(transactions.execute(status->{
            lockAllocator();StorageRow current=readRowLocked(id);
            if(current.workspaceId()!=null&&!Objects.equals(current.workspaceId(),tenantId))throw forbidden();
            if(current.enabled()&&!physicalRoot(id).equals(target.toString()))throw fault("STORAGE_DISABLE_BEFORE_ROOT_CHANGE",409,"更换目录前请先停用存储源");
            ensureDisjoint(target,id);requireActorUnchanged(actor);
            jdbc.update("UPDATE sc_drive_mount SET mount_key=?,name=?,workspace_id=?,physical_root=?,enabled=?,revision=revision+CASE WHEN enabled<>? OR physical_root<>? OR name<>? OR mount_key<>? OR (workspace_id<>? OR (workspace_id IS NULL AND ? IS NOT NULL) OR (workspace_id IS NOT NULL AND ? IS NULL)) THEN 1 ELSE 0 END WHERE id=? AND engine='LOCAL'",
                    updateKey,name,tenantId,target.toString(),enabled,enabled,target.toString(),name,updateKey,tenantId,tenantId,tenantId,id);
            insertMetadata(id,input,remark);provisionAdministrators(id,target,administrators);return id;
        }));}catch(DuplicateKeyException duplicate){throw fault("STORAGE_KEY_CONFLICT",409,"存储别名已存在，请换一个别名");}
    }

    public Map<String,Object> testConnection(Actor supplied,Map<String,Object> input){
        requireAdmin(supplied);if(input==null||!"local".equalsIgnoreCase(asString(input.get("type"))))throw fault("STORAGE_TYPE_UNAVAILABLE",501,"当前仅支持服务器本地存储连接检查");
        Map<?,?> params=input.get("storageSourceAllParam") instanceof Map<?,?> values?values:Map.of();Path path=managedChild(asString(params.get("filePath")));
        boolean write=bool(input.get("verifyWrite"),false);try{probe(path,write);}catch(ApplicationFault failure){if("STORAGE_PROBE_CLEANUP_FAILED".equals(failure.code()))return Map.of("passed",false,"cleanup",false,"message","读写检查未通过，临时文件清理失败；请由管理员检查受控目录。");throw failure;}
        return Map.of("passed",true,"cleanup",true,"message",write?"读写测试成功，临时文件已清理。":"只读连接检查成功；未写入或修改目录。");
    }

    public LocalStorageRequestView requestLocal(Actor supplied,String reason){Actor actor=requireTenantAdmin(supplied);String purpose=text(reason,500,"申请用途须为 1 至 500 字符");LocalStorageRequestView requested=workspaceCloud.requestLocal(actor,"服务器本地云盘");
        if("PENDING".equals(requested.status()))jdbc.update("UPDATE sc_local_storage_request SET reason=? WHERE id=? AND requester_id=? AND status='PENDING'",purpose,requested.id(),actor.id());
        return jdbc.queryForObject("SELECT id,requester_id,workspace_id,name,status,mount_id,decided_by,reason,created_at,decided_at FROM sc_local_storage_request WHERE id=? AND requester_id=?",
                (r,n)->new LocalStorageRequestView(r.getLong("id"),r.getLong("requester_id"),r.getLong("workspace_id"),r.getString("name"),r.getString("status"),r.getObject("mount_id",Long.class),r.getObject("decided_by",Long.class),r.getString("reason"),java.time.Instant.ofEpochMilli(r.getLong("created_at")),r.getObject("decided_at",Long.class)==null?null:java.time.Instant.ofEpochMilli(r.getLong("decided_at"))),requested.id(),actor.id());}

    public void setEnabled(Actor supplied,long id,boolean enable){requireAdmin(supplied);transactions.executeWithoutResult(status->{lockAllocator();readRowLocked(id);requireActorUnchanged(requireAdmin(supplied));jdbc.update("UPDATE sc_drive_mount SET enabled=?,revision=revision+1 WHERE id=? AND engine='LOCAL' AND enabled<>?",enable,id,enable);});}
    public void setCache(Actor supplied,long id,boolean enable){requireAdmin(supplied);readRow(id);if(enable)throw fault("STORAGE_CACHE_UNAVAILABLE",501,"当前本地存储未接入缓存能力");}
    public void setAutoRefresh(Actor supplied,long id,boolean enable){requireAdmin(supplied);readRow(id);if(enable)throw fault("STORAGE_CACHE_UNAVAILABLE",501,"当前本地存储未接入缓存自动刷新");}
    public void sort(Actor supplied,List<Map<String,Object>> input){
        requireAdmin(supplied);if(input==null||input.size()>200)throw invalid("排序列表无效");
        Set<Long> seen=new LinkedHashSet<>();
        for(Map<String,Object> row:input){Long id=row==null?null:number(row.get("id"));if(id==null||!seen.add(id))throw invalid("排序列表含重复或无效 ID");}
        transactions.executeWithoutResult(status->{
            for(long id:seen.stream().sorted().toList())if(jdbc.queryForList("SELECT id FROM sc_drive_mount WHERE id=? AND engine IN('LOCAL','S3','ALIYUN_OSS') FOR UPDATE",Long.class,id).size()!=1)throw missing();
            int order=0;for(long id:seen){ensureMetadata(id);jdbc.update("UPDATE sc_storage_admin_metadata SET order_num=? WHERE mount_id=?",order++,id);}
        });
    }
    public void delete(Actor supplied,long id,boolean confirmed){
        requireAdmin(supplied);if(!confirmed)throw fault("STORAGE_CONFIRM_REQUIRED",400,"请先确认删除存储源配置");
        transactions.executeWithoutResult(status->{
            lockAllocator();StorageRow row=readRowLocked(id);
            if(row.enabled())throw fault("STORAGE_DISABLE_BEFORE_DELETE",409,"请先停用存储源；删除只移除连接配置，不会删除实际文件");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sc_cloud_access_request WHERE mount_id=?",Boolean.class,id)))
                throw fault("STORAGE_REQUEST_HISTORY_EXISTS",409,"此存储源已有成员云盘申请记录，请保持停用以保留申请历史；当前不能删除此配置");
            jdbc.update("DELETE FROM sc_drive_grant WHERE mount_id=?",id);
            jdbc.update("UPDATE sc_local_storage_request SET mount_id=NULL WHERE mount_id=?",id);
            jdbc.update("DELETE FROM sc_storage_admin_metadata WHERE mount_id=?",id);
            if(jdbc.update("DELETE FROM sc_drive_mount WHERE id=? AND engine='LOCAL' AND enabled=FALSE",id)!=1)throw missing();
        });
    }
    public boolean existsKey(Actor supplied,String key){requireAdminOrTenantAdmin(supplied);validateKey(key);return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sc_drive_mount WHERE mount_key=?",Boolean.class,key));}

    public List<Map<String,Object>> workspaceClouds(Actor supplied){Actor actor=current(supplied);if(actor.role()!=Role.USER)throw forbidden();
        List<Map<String,Object>> rows=jdbc.query("SELECT m.id,m.name,COALESCE(g.enabled,FALSE) granted,g.quota_bytes,"
                +"(SELECT r.status FROM sc_cloud_access_request r WHERE r.requester_id=? AND r.mount_id=m.id ORDER BY r.id DESC LIMIT 1) request_status,"
                +"(SELECT r.reason FROM sc_cloud_access_request r WHERE r.requester_id=? AND r.mount_id=m.id ORDER BY r.id DESC LIMIT 1) request_reason "
                +"FROM sc_drive_mount m LEFT JOIN sc_drive_grant g ON g.mount_id=m.id AND g.user_id=? WHERE m.workspace_id=? AND m.enabled=TRUE ORDER BY m.id LIMIT 200",
                (r,n)->{boolean granted=r.getBoolean("granted");String state=r.getString("request_status");String status=granted?"APPROVED":state==null?"NONE":state;var row=new LinkedHashMap<String,Object>();row.put("id",r.getLong("id"));row.put("name",r.getString("name"));row.put("granted",granted);row.put("status",status);row.put("quotaBytes",r.getObject("quota_bytes",Long.class));row.put("reason",r.getString("request_reason"));return row;},actor.id(),actor.id(),actor.id(),actor.workspaceId());
        return rows;
    }

    public Map<String,Object> memberPermissions(Actor supplied,long userId){
        Actor admin=requireTenantAdmin(supplied);Actor member=member(admin,userId);
        List<GrantRow> grants=ownGrantedMounts(admin);
        Set<String> allowedDefaults=new LinkedHashSet<>();List<Map<String,Object>> storageRows=new ArrayList<>();
        for(GrantRow own:grants){allowedDefaults.addAll(own.permissions());GrantRow target=memberGrant(member.id(),own.mountId());String memberRoot=memberRoot(member.id());
            var row=new LinkedHashMap<String,Object>();row.put("storageSourceId",own.mountId());row.put("storageSourceName",own.name());row.put("enable",target!=null&&target.enabled());row.put("rootPath",target==null?memberRoot:target.root());row.put("allowedRootPath",memberRoot);row.put("quotaBytes",target==null?null:target.quotaBytes());row.put("permissions",target==null?List.of():target.permissions());row.put("allowedPermissions",own.permissions().stream().sorted().toList());storageRows.add(row);}
        List<String> defaults=jdbc.query("SELECT permissions FROM sc_workspace_member_permission_defaults WHERE user_id=? AND workspace_id=?",(r,n)->permissionCsv(r.getString(1)).stream().sorted().toList(),member.id(),admin.workspaceId()).stream().findFirst().orElse(List.of());
        var result=new LinkedHashMap<String,Object>();result.put("userId",member.id());result.put("defaultPermissions",defaults);result.put("allowedDefaultPermissions",allowedDefaults.stream().sorted().toList());result.put("permissionOptions",PERMISSION_OPTIONS);result.put("storages",storageRows);return result;
    }

    public void saveMemberPermissions(Actor supplied,long userId,MemberPermissionsInput input){
        Actor admin=requireTenantAdmin(supplied);Actor member=member(admin,userId);if(input==null||input.storages()==null||input.storages().size()>200)throw invalid("成员权限数据无效");
        List<MemberStorageInput> storages=new ArrayList<>(input.storages());Set<Long> seen=new LinkedHashSet<>();
        for(MemberStorageInput storage:storages)if(storage==null||storage.storageSourceId()==null||storage.storageSourceId()<1||!seen.add(storage.storageSourceId()))throw invalid("存储授权列表含重复或无效 ID");
        storages.sort(Comparator.comparing(MemberStorageInput::storageSourceId));
        Set<String> allowedDefaults=new LinkedHashSet<>();for(GrantRow own:ownGrantedMounts(admin))allowedDefaults.addAll(own.permissions());
        Set<String> defaults=validatedPermissions(input.defaultPermissions());if(!allowedDefaults.containsAll(defaults))throw fault("DRIVE_PERMISSION_SCOPE",403,"默认权限不能超过当前空间管理员已有授权");
        transactions.executeWithoutResult(status->{
            requireActorUnchanged(admin);
            // File operations also lock the mount before any grant. Lock every selected mount in ID
            // order before taking grants so reversed multi-mount edits cannot form a lock cycle.
            for(MemberStorageInput storage:storages){
                List<Long> locked=jdbc.queryForList("SELECT id FROM sc_drive_mount WHERE id=? AND workspace_id=? AND engine IN('LOCAL','S3','ALIYUN_OSS') AND enabled=TRUE FOR UPDATE",Long.class,storage.storageSourceId(),admin.workspaceId());
                if(locked.size()!=1)throw fault("DRIVE_PERMISSION_SCOPE",403,"不能授权本空间管理员未持有的存储源");
            }
            for(MemberStorageInput storage:storages){
                String ownedRoot=memberRoot(member.id());if(!Objects.equals(ownedRoot,normalizeRoot(storage.rootPath())))throw fault("DRIVE_ROOT_SCOPE",403,"成员只能使用系统分配的个人目录");
                List<String> ownRows=jdbc.query("SELECT permissions FROM sc_drive_grant WHERE user_id=? AND mount_id=? AND enabled=TRUE FOR UPDATE",(r,n)->r.getString(1),admin.id(),storage.storageSourceId());
                if(ownRows.size()!=1)throw fault("DRIVE_PERMISSION_SCOPE",403,"不能授权本空间管理员未持有的存储源");
                Set<String> own=permissionCsv(ownRows.getFirst());Set<String> selected=Boolean.TRUE.equals(storage.enable())?validatedPermissions(storage.permissions()):Set.of();
                if(Boolean.TRUE.equals(storage.enable())&&selected.isEmpty())throw invalid("启用权限时至少选择一项操作");if(!own.containsAll(selected))throw fault("DRIVE_PERMISSION_SCOPE",403,"成员权限不能超过空间管理员已有授权");
                validateQuota(storage.quotaBytes());List<String> targetRows=jdbc.query("SELECT root_path FROM sc_drive_grant WHERE user_id=? AND mount_id=? FOR UPDATE",(r,n)->r.getString(1),member.id(),storage.storageSourceId());
                if(targetRows.isEmpty())jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,'',NULL,FALSE,1,?)",member.id(),storage.storageSourceId(),ownedRoot,clock.millis());
                workspaceCloud.updateMemberGrant(admin,member.id(),storage.storageSourceId(),storage.quotaBytes(),selected);
            }
            jdbc.update("UPDATE sc_workspace_member_permission_defaults SET permissions=?,updated_at=? WHERE user_id=? AND workspace_id=?",
                    permissionCsv(defaults),clock.millis(),member.id(),admin.workspaceId());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_member_permission_defaults WHERE user_id=? AND workspace_id=?",Integer.class,member.id(),admin.workspaceId())==0)
                jdbc.update("INSERT INTO sc_workspace_member_permission_defaults(user_id,workspace_id,permissions,updated_at) VALUES(?,?,?,?)",member.id(),admin.workspaceId(),permissionCsv(defaults),clock.millis());
        });
    }

    private MountViewHolder createMount(Actor actor,String name,String key,Path root,Long workspace){
        var mount=mounts.createLocal(actor,name,key,root,workspace);return new MountViewHolder(mount.id());
    }
    private void provisionAdministrators(long mountId,Path root,List<Actor> administrators){
        if(administrators.isEmpty())return;
        try{
            var store=new LocalFileStore(root,10000,MountService.MAX_TRANSFER_BYTES);
            store.createDirectory(DrivePath.of("/admins"));
            for(Actor administrator:administrators){
                store.createDirectory(DrivePath.of(adminRoot(administrator.id())));
                if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_grant WHERE user_id=? AND mount_id=?",Integer.class,administrator.id(),mountId)==0)
                    jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(?,?,?,?,TRUE,1,?)",
                            administrator.id(),mountId,adminRoot(administrator.id()),permissionCsv(PERMISSIONS),clock.millis());
            }
        }catch(IOException failure){throw fault("DRIVE_PROVISION_FAILED",409,"空间管理员个人目录创建失败，请检查受控目录");}
    }
    private void insertMetadata(long id,Map<String,Object> input,String remark){
        ensureMetadata(id);jdbc.update("UPDATE sc_storage_admin_metadata SET order_num=?,remark=?,default_switch_to_img_mode=?,enable_file_operator=?,enable_file_anno_operator=? WHERE mount_id=?",
                integer(input.get("orderNum"),0),remark,bool(input.get("defaultSwitchToImgMode"),false),bool(input.get("enableFileOperator"),true),bool(input.get("enableFileAnnoOperator"),false),id);
    }
    private void ensureMetadata(long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_storage_admin_metadata WHERE mount_id=?",Integer.class,id)==0)jdbc.update("INSERT INTO sc_storage_admin_metadata(mount_id) VALUES(?)",id);}
    private void lockAllocator(){jdbc.queryForObject("SELECT id FROM sc_content_settings WHERE id=1 FOR UPDATE",Integer.class);}
    private void ensureDisjoint(Path candidate,Long excluded){List<String> roots=excluded==null?jdbc.queryForList("SELECT physical_root FROM sc_drive_mount WHERE engine='LOCAL'",String.class):jdbc.queryForList("SELECT physical_root FROM sc_drive_mount WHERE engine='LOCAL' AND id<>?",String.class,excluded);
        for(String existing:roots){Path allocated=Path.of(existing).toAbsolutePath().normalize();if(candidate.startsWith(allocated)||allocated.startsWith(candidate))throw fault("STORAGE_ROOT_OVERLAP",409,"本地存储目录不能与其他存储源重叠");}}
    private Path managedChild(String raw){if(managedRoot==null)throw fault("DRIVE_NOT_CONFIGURED",503,"尚未配置独立后端受控存储根目录");try{Path managed=managedRoot.toRealPath();Path candidate=Path.of(Objects.requireNonNull(raw)).toAbsolutePath().normalize();if(!candidate.startsWith(managed)||candidate.equals(managed)||!Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS))throw denied();Path canonical=candidate.toRealPath();if(!candidate.equals(canonical)||!canonical.startsWith(managed))throw denied();new LocalFileStore(canonical,MountService.MAX_ENTRIES,MountService.MAX_TRANSFER_BYTES);return canonical;}catch(ApplicationFault known){throw known;}catch(Exception invalid){throw fault("STORAGE_PATH_INVALID",400,"请选择受控根目录下已存在且不含符号链接的目录");}}
    private void probe(Path path,boolean verifyWrite){try{if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(path))throw fault("STORAGE_READ_FAILED",409,"目录不可读");LocalFileStore store=new LocalFileStore(path,MountService.MAX_ENTRIES,MountService.MAX_TRANSFER_BYTES);store.list(DrivePath.of("/"));if(!verifyWrite)return;if(!Files.isWritable(path))throw fault("STORAGE_WRITE_FAILED",409,"目录不可写");Path probe=path.resolve(".student-cloud-preflight-"+UUID.randomUUID()+".tmp");byte[] content=UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);boolean created=false;try{Files.createFile(probe);created=true;Files.write(probe,content,java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);if(!Arrays.equals(content,Files.readAllBytes(probe)))throw fault("STORAGE_VERIFY_FAILED",409,"临时文件回读校验失败");}finally{if(created)try{Files.deleteIfExists(probe);}catch(IOException cleanup){throw fault("STORAGE_PROBE_CLEANUP_FAILED",409,"临时文件未能清理");}}}catch(ApplicationFault known){throw known;}catch(IOException failure){throw fault("STORAGE_PROBE_FAILED",409,"本地目录读写检查失败；尚未保存配置");}}
    private StorageRow readRow(long id){List<StorageRow> rows=jdbc.query("SELECT m.id,m.mount_key,m.name,m.workspace_id,m.enabled,COALESCE(a.order_num,0) order_num,a.remark,COALESCE(a.default_switch_to_img_mode,FALSE) default_switch_to_img_mode,COALESCE(a.enable_file_operator,TRUE) enable_file_operator,COALESCE(a.enable_file_anno_operator,FALSE) enable_file_anno_operator,COALESCE(a.enable_cache,FALSE) enable_cache,COALESCE(a.auto_refresh_cache,FALSE) auto_refresh_cache FROM sc_drive_mount m LEFT JOIN sc_storage_admin_metadata a ON a.mount_id=m.id WHERE m.id=? AND m.engine='LOCAL'",this::storageRow,id);if(rows.size()!=1)throw missing();return rows.getFirst();}
    private StorageRow readRowLocked(long id){List<StorageRow> rows=jdbc.query("SELECT m.id,m.mount_key,m.name,m.workspace_id,m.enabled,COALESCE(a.order_num,0) order_num,a.remark,COALESCE(a.default_switch_to_img_mode,FALSE) default_switch_to_img_mode,COALESCE(a.enable_file_operator,TRUE) enable_file_operator,COALESCE(a.enable_file_anno_operator,FALSE) enable_file_anno_operator,COALESCE(a.enable_cache,FALSE) enable_cache,COALESCE(a.auto_refresh_cache,FALSE) auto_refresh_cache FROM sc_drive_mount m LEFT JOIN sc_storage_admin_metadata a ON a.mount_id=m.id WHERE m.id=? AND m.engine='LOCAL' FOR UPDATE",this::storageRow,id);if(rows.size()!=1)throw missing();return rows.getFirst();}
    private StorageRow storageRow(ResultSet r,int n)throws SQLException{return new StorageRow(r.getLong("id"),r.getString("mount_key"),r.getString("name"),r.getObject("workspace_id",Long.class),r.getBoolean("enabled"),r.getInt("order_num"),r.getString("remark"),r.getBoolean("default_switch_to_img_mode"),r.getBoolean("enable_file_operator"),r.getBoolean("enable_file_anno_operator"),r.getBoolean("enable_cache"),r.getBoolean("auto_refresh_cache"));}
    private Map<String,Object> rowDto(StorageRow row,boolean global){var value=new LinkedHashMap<String,Object>();value.put("id",row.id());value.put("key",row.key());value.put("name",row.name());value.put("tenantId",row.workspaceId());value.put("tenantName",null);value.put("type",Map.of("key","local","description","服务器本地存储"));value.put("enable",row.enabled());value.put("enableCache",false);value.put("autoRefreshCache",false);value.put("cacheAvailable",false);value.put("orderNum",row.orderNum());value.put("remark",global?row.remark():null);value.put("defaultSwitchToImgMode",row.defaultSwitchToImgMode());value.put("enableFileOperator",row.enableFileOperator());value.put("enableFileAnnoOperator",row.enableFileAnnoOperator());return value;}
    private String physicalRoot(long id){return jdbc.queryForObject("SELECT physical_root FROM sc_drive_mount WHERE id=? AND engine='LOCAL'",String.class,id);}
    private String readKey(long id){return jdbc.queryForObject("SELECT mount_key FROM sc_drive_mount WHERE id=? AND engine='LOCAL'",String.class,id);}
    private void requireActorUnchanged(Actor before){Actor after=current(before);if(after.id()!=before.id()||after.role()!=before.role()||!Objects.equals(after.workspaceId(),before.workspaceId()))throw fault("STORAGE_ACTOR_CHANGED",409,"账号或工作空间已变化，请重新登录");}
    private Actor current(Actor supplied){Actor actor=accounts.current(supplied);if(actor.role()!=Role.ADMIN&&actor.role()!=Role.TENANT_ADMIN&&actor.role()!=Role.USER)throw forbidden();return actor;}
    private Actor requireAdminOrTenantAdmin(Actor supplied){Actor actor=current(supplied);if(actor.role()!=Role.ADMIN&&actor.role()!=Role.TENANT_ADMIN)throw forbidden();return actor;}
    private Actor requireAdmin(Actor supplied){Actor actor=current(supplied);if(actor.role()!=Role.ADMIN||actor.workspaceId()!=null)throw forbidden();return actor;}
    private Actor requireTenantAdmin(Actor supplied){Actor actor=current(supplied);if(actor.role()!=Role.TENANT_ADMIN||actor.workspaceId()==null)throw forbidden();return actor;}
    private Actor member(Actor admin,long id){Actor member=accounts.account(id);if(member.role()!=Role.USER||!Objects.equals(member.workspaceId(),admin.workspaceId()))throw forbidden();return member;}
    private List<GrantRow> ownGrantedMounts(Actor admin){return jdbc.query("SELECT m.id,m.name,g.root_path,g.permissions,g.quota_bytes,g.enabled FROM sc_drive_mount m JOIN sc_drive_grant g ON g.mount_id=m.id WHERE m.workspace_id=? AND m.engine IN('LOCAL','S3','ALIYUN_OSS') AND m.enabled=TRUE AND g.user_id=? AND g.enabled=TRUE ORDER BY m.id LIMIT 200",(r,n)->{String root=normalizeRoot(r.getString("root_path"));if(!Objects.equals(root,adminRoot(admin.id())))throw forbidden();return new GrantRow(r.getLong("id"),r.getString("name"),root,permissionCsv(r.getString("permissions")),r.getObject("quota_bytes",Long.class),true);},admin.workspaceId(),admin.id());}
    private GrantRow memberGrant(long userId,long mountId){return jdbc.query("SELECT m.id,m.name,g.root_path,g.permissions,g.quota_bytes,g.enabled FROM sc_drive_mount m LEFT JOIN sc_drive_grant g ON g.mount_id=m.id AND g.user_id=? WHERE m.id=? AND m.engine IN('LOCAL','S3','ALIYUN_OSS')",(r,n)->{String root=r.getString("root_path");return root==null?null:new GrantRow(r.getLong("id"),r.getString("name"),normalizeRoot(root),permissionCsv(r.getString("permissions")),r.getObject("quota_bytes",Long.class),r.getBoolean("enabled"));},userId,mountId).stream().filter(Objects::nonNull).findFirst().orElse(null);}
    private static Set<String> validatedPermissions(List<String> values){if(values==null)return Set.of();Set<String> result=new LinkedHashSet<>(values);if(result.size()!=values.size()||!PERMISSIONS.containsAll(result))throw invalid("权限项无效");return Set.copyOf(result);}
    private static Set<String> permissionCsv(String value){if(value==null||value.length()>1024)throw denied();if(value.isBlank())return Set.of();return validatedPermissions(Arrays.asList(value.split(",",-1)));}
    private static String permissionCsv(Set<String> values){return values.stream().sorted().collect(java.util.stream.Collectors.joining(","));}
    private static void validateQuota(Long value){if(value!=null&&(value<0||value>MAX_QUOTA_BYTES))throw invalid("容量上限须为 0 至 102400 GB");}
    private String readKeySafely(long id){return readKey(id);}
    private static String memberRoot(long id){return "/members/u"+id;}
    private static String adminRoot(long id){return "/admins/u"+id;}
    private static String normalizeRoot(String value){try{return DrivePath.of(value).value();}catch(RuntimeException invalid){throw denied();}}
    private static Map<String,Object> option(String value,String name){return Map.of("value",value,"name",name);}
    private static String slug(String name){String base=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+","-").replaceAll("^-+|-+$","");if(base.length()<3)base="local-storage";if(base.length()>45)base=base.substring(0,45);return base+"-"+UUID.randomUUID().toString().substring(0,8);}
    private static void validateKey(String value){if(value==null||!value.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid("存储别名须为3至64位小写字母、数字、横线或下划线");}
    private static String text(String value,int max,String message){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw invalid(message);return value.trim();}
    private static String optionalText(String value,int max,String message){if(value==null||value.isBlank())return null;return text(value,max,message);}
    private static String asString(Object value){return value instanceof String s?s:null;}
    private static Long number(Object value){if(value==null)return null;if(value instanceof Number n){long result=n.longValue();if(result<1)throw invalid("ID 无效");return result;}try{long result=Long.parseLong(String.valueOf(value));if(result<1)throw invalid("ID 无效");return result;}catch(NumberFormatException e){throw invalid("ID 无效");}}
    private static boolean bool(Object value,boolean fallback){return value==null?fallback:value instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(value));}
    private static int integer(Object value,int fallback){if(value==null)return fallback;if(value instanceof Number n){if(n.intValue()<0||n.intValue()>100000)throw invalid("排序值无效");return n.intValue();}try{return Integer.parseInt(value.toString());}catch(NumberFormatException e){throw invalid("排序值无效");}}
    private static ApplicationFault invalid(String message){return fault("STORAGE_INVALID",400,message);}
    private static ApplicationFault forbidden(){return fault("STORAGE_FORBIDDEN",403,"当前账号不能执行此存储管理操作");}
    private static ApplicationFault denied(){return forbidden();}
    private static ApplicationFault missing(){return fault("STORAGE_NOT_FOUND",404,"存储源不存在或不可用");}
    private static ApplicationFault fault(String code,int status,String message){return new ApplicationFault(code,status,message);}
    private record StorageRow(long id,String key,String name,Long workspaceId,boolean enabled,int orderNum,String remark,boolean defaultSwitchToImgMode,boolean enableFileOperator,boolean enableFileAnnoOperator,boolean enableCache,boolean autoRefreshCache){}
    private record GrantRow(long mountId,String name,String root,Set<String> permissions,Long quotaBytes,boolean enabled){}
    private record MountViewHolder(long id){}
}
