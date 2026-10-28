package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import io.studentcloud.drive.LocalFileStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;
import org.springframework.transaction.TransactionDefinition;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit authorization followed by the independent kernel, with no legacy storage or session state. */
public final class MountService implements DriveOperations {
    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_TRANSFER_BYTES = 50L * 1024 * 1024;
    private static final Set<String> KNOWN = Set.of("list", "read", "mkdir", "upload", "copy", "move", "rename", "delete");
    private static final String COLUMNS = "m.id,m.mount_key,m.name,m.workspace_id,m.owner_id,m.engine,m.physical_root,m.enabled,m.revision";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final Path managedRoot;
    private final Semaphore stagedUploads = new Semaphore(2);
    private FileCatalog catalog;

    public MountService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock, Path managedRoot, AccountDirectory accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(30);
        this.clock = Objects.requireNonNull(clock);
        this.accounts = Objects.requireNonNull(accounts);
        this.managedRoot = managedRoot == null ? null : managedRoot.toAbsolutePath().normalize();
    }
    public MountService(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock,Path root,AccountDirectory accounts,FileCatalog catalog){
        this(jdbc,transactions,clock,root,accounts);this.catalog=Objects.requireNonNull(catalog);
    }

    public List<MountView> listMounts(Actor actor) {
        return protect(()->{configuredRoot();return listLocalMounts(actor);});
    }

    /** Metadata discovery must not make healthy remote connections depend on a local disk. */
    public List<MountView> listLocalMounts(Actor actor) {
        return protect(() -> {
            Actor current = currentActor(actor);
            List<MountRow> rows = current.role() == Role.ADMIN
                    ? jdbc.query("SELECT " + COLUMNS + " FROM sc_drive_mount m WHERE m.engine='LOCAL' ORDER BY m.id LIMIT 201", this::mount)
                    : jdbc.query("SELECT " + COLUMNS + " FROM sc_drive_mount m JOIN sc_drive_grant g ON g.mount_id=m.id WHERE m.engine='LOCAL' AND m.workspace_id=? AND g.user_id=? AND m.enabled=TRUE AND g.enabled=TRUE ORDER BY m.id LIMIT 201", this::mount, current.workspaceId(), current.id());
            if (rows.size() > 200) throw fault("DRIVE_BUDGET", 409, "连接数量超出当前列表预算，请缩小范围");
            java.util.ArrayList<MountView> result = new java.util.ArrayList<>();
            for (MountRow row : rows) {
                if (current.role() == Role.ADMIN) result.add(row.view(KNOWN));
                else {
                    try { result.add(row.view(requireGrant(current, row).permissions())); }
                    catch (ApplicationFault invalid) { if (invalid.httpStatus() != 403) throw invalid; }
                }
            }
            return List.copyOf(result);
        });
    }

    public MountView createLocal(Actor actor, String name, String key, Path root) {
        return createLocal(actor, name, key, root, null);
    }

    /** ADMIN may optionally bind a connection to an existing workspace; this does not create any grant. */
    public MountView createLocal(Actor actor, String name, String key, Path root, Long workspaceId) {
        return protect(() -> {
            Actor current = currentActor(actor);
            if (current.role() != Role.ADMIN) throw denied();
            String label = requiredName(name);
            String connectionKey = requiredKey(key);
            Path physical = requireManagedRoot(root);
            try {
                return transactions.execute(status -> {
                    // Serialize root allocation, including approvals calling this method in a surrounding
                    // transaction. Separate mount locks are safe only for disjoint physical trees.
                    jdbc.queryForObject("SELECT id FROM sc_content_settings WHERE id=1 FOR UPDATE", Integer.class);
                    Actor lockedActor = currentActor(actor);
                    if (lockedActor.role() != Role.ADMIN) throw denied();
                    requireDisjointMount(physical);
                    if (workspaceId != null) {
                        if (!accounts.workspaceEnabled(workspaceId)) throw denied();
                    }
                    GeneratedKeyHolder generated = new GeneratedKeyHolder();
                    jdbc.update(connection -> {
                        PreparedStatement statement = connection.prepareStatement("INSERT INTO sc_drive_mount(mount_key,name,workspace_id,owner_id,engine,physical_root,enabled,revision,created_at) VALUES(?,?,?,?,'LOCAL',?,TRUE,1,?)", Statement.RETURN_GENERATED_KEYS);
                        statement.setString(1, connectionKey);
                        statement.setString(2, label);
                        if (workspaceId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, workspaceId);
                        statement.setLong(4, lockedActor.id());
                        statement.setString(5, physical.toString());
                        statement.setLong(6, clock.millis());
                        return statement;
                    }, generated);
                    long id = Objects.requireNonNull(generated.getKey()).longValue();
                    return new MountView(id, connectionKey, label, workspaceId, lockedActor.id(), "LOCAL", true, 1, KNOWN);
                });
            } catch (DuplicateKeyException duplicate) {
                throw fault("DRIVE_CONFLICT", 409, "此连接标识已经存在，请使用其他标识");
            }
        });
    }

    public List<FileRecord> list(Actor actor, String key, DrivePath path) {
        return list(actor,key,path,false);
    }
    public List<FileRecord> list(Actor actor,String key,DrivePath path,boolean refresh){
        return protect(()->{
            Access allowed=access(actor,key,Set.of("list"),false);requireDirectory(allowed,path);
            if(catalog==null)return allowed.store().list(path);
            long modified=allowed.store().stat(path).orElseThrow().modifiedAt().toEpochMilli();
            return catalog.list(allowed.stamp().userId(),allowed.stamp().mountId(),catalogScope(allowed),path,modified,refresh,
                ()->protect(()->allowed.store().list(path)));
        });
    }
    public FileCatalog.Page catalogPage(Actor actor,String key,DrivePath path,int page,int size,String query,boolean refresh){
        if(catalog==null)throw fault("CATALOG_UNAVAILABLE",501,"文件索引尚未启用");
        list(actor,key,path,refresh);
        return protect(()->{Access a=access(actor,key,Set.of("list"),false);requireDirectory(a,path);
            return catalog.page(a.stamp().userId(),a.stamp().mountId(),catalogScope(a),path,page,size,query);});
    }
    private String catalogScope(Access a)throws IOException{return vaultScope(a)+":"+a.stamp().mountRevision()+":"+a.stamp().grantRevision();}
    private String vaultScope(Access a)throws IOException{
        var attr=Files.readAttributes(visibleRoot(a),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return digest(a.stamp().physical()+"|"+a.stamp().root()+"|"+attr.fileKey()+"|"+attr.creationTime());
    }

    public Optional<FileRecord> stat(Actor actor, String key, DrivePath path) {
        return protect(() -> access(actor, key, Set.of("list"), false).store().stat(path));
    }

    /** Fresh, safe browser capabilities for an existing grant-relative directory. */
    public Set<String> directoryCapabilities(Actor actor, String key, DrivePath path) {
        return protect(() -> {
            Access allowed = access(actor, key, Set.of("list"), false);
            requireDirectory(allowed, path);
            return Set.copyOf(allowed.stamp().permissions());
        });
    }

    /** Read-only upload preflight. Publication still reauthorizes and validates inside upload(). */
    public void prepareUpload(Actor actor, String key, DrivePath path, long size) {
        protect(() -> {
            Access allowed = access(actor, key, Set.of("upload"), true);
            validateUpload(allowed, path, size);
            return null;
        });
    }

    /** A stable authorization generation, independent of target existence and directory mtime. */
    public String uploadAuthorization(Actor actor,String key,DrivePath path,long size){
        return protect(()->{
            Access access=access(actor,key,Set.of("upload"),true);
            if(path.isRoot()||size<1||size>MAX_TRANSFER_BYTES)throw fault("DRIVE_INVALID",400,"文件大小或名称无效");
            requireDirectory(access,path.parent());
            Path directory=visibleRoot(access).resolve(path.parent().relative());
            var attributes=Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            Stamp s=access.stamp();
            String value=s.userId()+"|"+s.role()+"|"+s.workspaceId()+"|"+s.mountId()+"|"+s.mountRevision()+"|"+s.physical()+"|"+s.root()+"|"+
                s.permissions().stream().sorted().collect(java.util.stream.Collectors.joining(","))+"|"+s.quota()+"|"+s.grantRevision()+"|"+attributes.fileKey()+"|"+attributes.creationTime();
            return digest(value);
        });
    }
    private static String digest(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}

    private static void requireDirectory(Access allowed, DrivePath path) throws IOException {
        FileRecord item = allowed.store().stat(path).orElseThrow(() -> fault("DRIVE_NOT_FOUND", 404, "文件夹不存在"));
        if (!item.directory()) throw fault("DRIVE_INVALID", 400, "请选择文件夹");
    }

    private static void validateUpload(Access allowed, DrivePath path, long size) throws IOException {
        if (size < 0 || size > MAX_TRANSFER_BYTES) throw fault("DRIVE_INVALID", 400, "文件不能超过50MiB");
        if (path.isRoot()) throw fault("DRIVE_INVALID", 400, "请指定文件名称");
        requireDirectory(allowed, path.parent());
        if (allowed.store().stat(path).isPresent()) throw fault("DRIVE_CONFLICT", 409, "目标已存在同名文件，不会覆盖");
    }

    public FileRecord mkdir(Actor actor, String key, DrivePath path) {
        return mutate(actor, key, Set.of("mkdir"), null, allowed -> 0L, allowed -> allowed.store().createDirectory(path));
    }

    public FileRecord upload(Actor actor, String key, DrivePath path, InputStream input, long size) {
        return protect(() -> {
            Access authorized = access(actor, key, Set.of("upload"), true);
            validateUpload(authorized, path, size);
            if (!stagedUploads.tryAcquire()) throw fault("DRIVE_BUSY", 429, "上传暂存繁忙，请稍后重试");
            Path temporary = null;
            try {
                temporary = Files.createTempFile("student-cloud-upload-", ".tmp");
                if (temporary.toRealPath().startsWith(configuredRoot())) {
                    throw fault("DRIVE_STAGING_INVALID", 503, "上传暂存必须与可浏览存储隔离");
                }
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[16 * 1024];
                    long written = 0;
                    long started = System.nanoTime();
                    int emptyReads = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (System.nanoTime() - started >= 60_000_000_000L)
                            throw fault("DRIVE_BUSY", 408, "上传暂存超时，请重试");
                        if (count == 0) {
                            if (++emptyReads > 1024) throw fault("DRIVE_INVALID", 400, "文件流未返回有效数据");
                            continue;
                        }
                        emptyReads = 0;
                        written += count;
                        if (written > size) throw fault("DRIVE_INVALID", 400, "文件实际大小与声明不一致");
                        output.write(buffer, 0, count);
                    }
                    if (written != size) throw fault("DRIVE_INVALID", 400, "文件实际大小与声明不一致");
                }
                Path staged = temporary;
                return mutate(actor, key, Set.of("upload"), authorized.stamp(), allowed -> size, allowed -> {
                    try (InputStream ready = Files.newInputStream(staged)) {
                        return allowed.store().write(path, ready, size, false);
                    }
                });
            } finally {
                try { if (temporary != null) Files.deleteIfExists(temporary); }
                finally { stagedUploads.release(); }
            }
        });
    }

    public ReadHandle readWithMetadata(Actor actor, String key, DrivePath path) {
        return protect(() -> {
            Access authorized = access(actor, key, Set.of("read"), false);
            FileRecord metadata = authorized.store().stat(path).orElseThrow(() -> fault("DRIVE_NOT_FOUND", 404, "文件不存在"));
            return new ReadHandle(metadata, authorized.store().read(path));
        });
    }

    public FileRecord copy(Actor actor, String key, DrivePath source, DrivePath target) {
        return mutate(actor, key, Set.of("read", "copy"), null,
                allowed -> {
                    allowed.store().stat(source).orElseThrow(() -> fault("DRIVE_NOT_FOUND", 404, "文件或目录不存在"));
                    return LocalUsageMeter.bytes(visibleRoot(allowed).resolve(source.relative()));
                },
                allowed -> allowed.store().copy(source, target));
    }

    public FileRecord move(Actor actor, String key, DrivePath source, DrivePath target) {
        return mutate(actor, key, Set.of("move"), null, allowed -> 0L, allowed -> allowed.store().move(source, target));
    }

    public FileRecord rename(Actor actor, String key, DrivePath source, String name) {
        return mutate(actor, key, Set.of("rename"), null, allowed -> 0L, allowed -> allowed.store().move(source, source.parent().child(name)));
    }

    public void delete(Actor actor, String key, DrivePath path) {
        if(catalog!=null){recycle(actor,key,path);return;}
        mutate(actor, key, Set.of("delete"), null, allowed -> 0L, allowed -> {
            allowed.store().delete(path);
            return null;
        });
    }

    private <T> T mutate(Actor actor, String key, Set<String> operations, Stamp expected,
                         Mutation<Long> incoming, Mutation<T> action) {
        return protect(() -> {
            Access before = access(actor, key, operations, true);
            if (expected != null && !expected.equals(before.stamp())) throw fault("DRIVE_CHANGED", 409, "目录或授权已变化，请刷新后重试");
            return transactions.execute(status -> protect(() -> {
                // Every mutation, including ADMIN, serializes on the mount across application instances.
                jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE", Long.class, before.stamp().mountId());
                if (before.stamp().role() != Role.ADMIN) {
                    jdbc.queryForObject("SELECT revision FROM sc_drive_grant WHERE user_id=? AND mount_id=? FOR UPDATE", Long.class,
                            before.stamp().userId(), before.stamp().mountId());
                }
                Access current = access(actor, key, operations, true);
                if (!before.stamp().equals(current.stamp())) throw fault("DRIVE_CHANGED", 409, "目录或授权已变化，请刷新后重试");
                long growth = incoming.run(current);
                Long quota = current.stamp().quota();
                if (growth > 0 && quota != null) {
                    long used = LocalUsageMeter.bytes(visibleRoot(current));
                    if (growth > quota || used > quota-growth) throw fault("DRIVE_QUOTA_EXCEEDED", 409, "个人云盘可用容量不足");
                }
                T result=action.run(current);
                if(catalog!=null)catalog.invalidateMount(current.stamp().mountId());
                return result;
            }));
        });
    }

    public record TrashView(String id,String name,String originalPath,boolean directory,long size,String status,long deletedAt,long expiresAt){}
    private record TrashRow(TrashView view,long actorId,Long workspaceId,long mountId,String scope,String root){}
    public List<TrashView> recycleBin(Actor actor,String key){
        return protect(()->{
            requireLifecycle();Access a=access(actor,key,Set.of("list"),false);
            return jdbc.query("SELECT * FROM sc_drive_trash WHERE actor_id=? AND mount_id=? AND scope_hash=? AND status IN ('TRASHED','UNCERTAIN','PREPARING','RESTORING','PURGING') ORDER BY created_at DESC,id LIMIT 200",
                (r,n)->trashRow(r).view(),a.stamp().userId(),a.stamp().mountId(),vaultScope(a));
        });
    }
    public TrashView recycle(Actor actor,String key,DrivePath path){
        return protect(()->{
            requireLifecycle();if(path.isRoot())throw fault("DRIVE_INVALID",400,"不能删除个人根目录");
            Access a=access(actor,key,Set.of("delete"),true);
            var file=a.store().stat(path).orElseThrow(()->fault("DRIVE_NOT_FOUND",404,"文件不存在"));
            long bytes=LocalUsageMeter.bytes(visibleRoot(a).resolve(path.relative()));
            String id=java.util.UUID.randomUUID().toString().replace("-","");long now=clock.millis();
            // Keep the PREPARING intent committed if a filesystem side effect outlives a later SQL rollback.
            transactions.executeWithoutResult(tx->{
                jdbc.update("INSERT INTO sc_drive_trash_guard(actor_id) VALUES(?) ON DUPLICATE KEY UPDATE actor_id=actor_id",a.stamp().userId());
                jdbc.queryForObject("SELECT actor_id FROM sc_drive_trash_guard WHERE actor_id=? FOR UPDATE",Long.class,a.stamp().userId());
                jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,a.stamp().mountId());
                Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_drive_trash WHERE actor_id=? AND status IN ('TRASHED','PREPARING','UNCERTAIN','RESTORING','PURGING')",Long.class,a.stamp().userId());
                if(count==null||count>=200)throw fault("TRASH_FULL",409,"回收站已达200项，请先清理");
                String scope=protect(()->vaultScope(a));
                jdbc.update("INSERT INTO sc_drive_trash(id,actor_id,workspace_id,mount_id,scope_hash,grant_root,original_path,file_name,is_directory,byte_size,status,created_at,expires_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PREPARING',?,?,?)",
                    id,a.stamp().userId(),a.stamp().workspaceId(),a.stamp().mountId(),scope,a.stamp().root(),path.value(),file.name(),file.directory(),bytes,now,now+7*86400000L,now);
            });
            try{
                mutate(actor,key,Set.of("delete"),a.stamp(),x->0L,x->{
                    x.store().recycle(path,id);
                    jdbc.update("UPDATE sc_drive_trash SET status='TRASHED',updated_at=? WHERE id=? AND status='PREPARING'",clock.millis(),id);return null;
                });
            }catch(RuntimeException failed){
                jdbc.update("UPDATE sc_drive_trash SET status='UNCERTAIN',updated_at=? WHERE id=? AND status='PREPARING'",clock.millis(),id);throw failed;
            }
            return ownedTrash(a,id,false).view();
        });
    }
    public void restoreRecycled(Actor actor,String key,String id){
        protect(()->{
            requireLifecycle();Access before=access(actor,key,Set.of("upload"),true);claimTrash(actor,key,before,id,"RESTORING");
            try{mutate(actor,key,Set.of("upload"),before.stamp(),a->0L,a->{
                TrashRow row=ownedTrash(a,id,true);
                if(!"RESTORING".equals(row.view().status()))throw fault("TRASH_STATE",409,"恢复状态已变化");
                DrivePath target=DrivePath.of(row.view().originalPath());requireDirectory(a,target.parent());
                a.store().restoreRecycled(id,target);
                jdbc.update("UPDATE sc_drive_trash SET status='RESTORED',updated_at=? WHERE id=?",clock.millis(),id);
                return null;
            });}catch(RuntimeException failed){uncertain(id,"RESTORING");throw failed;}return null;
        });
    }
    public void purgeRecycled(Actor actor,String key,String id){
        protect(()->{
            requireLifecycle();Access before=access(actor,key,Set.of("delete"),true);claimTrash(actor,key,before,id,"PURGING");
            try{mutate(actor,key,Set.of("delete"),before.stamp(),a->0L,a->{
                TrashRow row=ownedTrash(a,id,true);
                if(!"PURGING".equals(row.view().status()))throw fault("TRASH_STATE",409,"清理状态已变化");
                a.store().purgeRecycled(id);
                jdbc.update("UPDATE sc_drive_trash SET status='PURGED',updated_at=? WHERE id=?",clock.millis(),id);return null;
            });}catch(RuntimeException failed){uncertain(id,"PURGING");throw failed;}return null;
        });
    }
    private void claimTrash(Actor actor,String key,Access expected,String id,String intent){
        transactions.executeWithoutResult(tx->protect(()->{
            jdbc.queryForObject("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,expected.stamp().mountId());
            Access current=access(actor,key,Set.of(intent.equals("RESTORING")?"upload":"delete"),true);
            if(!expected.stamp().equals(current.stamp()))throw fault("DRIVE_CHANGED",409,"授权已变化，请刷新");
            var row=ownedTrash(current,id,true);
            if(!"TRASHED".equals(row.view().status()))throw fault("TRASH_STATE",409,"记录正在处理或需要核查");
            if(intent.equals("RESTORING")){
                if(row.view().expiresAt()<=clock.millis())throw fault("TRASH_STATE",409,"记录已过期");
                DrivePath target=DrivePath.of(row.view().originalPath());requireDirectory(current,target.parent());
                if(current.store().stat(target).isPresent())throw new FileAlreadyExistsException("Restore target exists");
            }
            jdbc.update("UPDATE sc_drive_trash SET status=?,updated_at=? WHERE id=?",intent,clock.millis(),id);return null;
        }));
    }
    private void uncertain(String id,String intent){jdbc.update("UPDATE sc_drive_trash SET status='UNCERTAIN',updated_at=? WHERE id=? AND status=?",clock.millis(),id,intent);}
    /** System retention: it operates only on recorded opaque vault IDs; never a client-supplied host path. */
    public int cleanExpiredRecycle(){
        requireLifecycle();var ids=jdbc.queryForList("SELECT id FROM sc_drive_trash WHERE status='TRASHED' AND expires_at<=? ORDER BY expires_at LIMIT 10",String.class,clock.millis());int cleaned=0;
        for(String id:ids){
            try{
                var snapshot=jdbc.query("SELECT * FROM sc_drive_trash WHERE id=?",(r,n)->trashRow(r),id);if(snapshot.isEmpty())continue;
                Boolean done=transactions.execute(tx->protect(()->{
                    jdbc.queryForList("SELECT id FROM sc_drive_mount WHERE id=? FOR UPDATE",Long.class,snapshot.getFirst().mountId());
                    var rows=jdbc.query("SELECT * FROM sc_drive_trash WHERE id=? FOR UPDATE",(r,n)->trashRow(r),id);
                    if(rows.isEmpty()||!"TRASHED".equals(rows.getFirst().view().status()))return false;TrashRow row=rows.getFirst();
                    var mounts=jdbc.query("SELECT "+COLUMNS+" FROM sc_drive_mount m WHERE m.id=? FOR UPDATE",this::mount,row.mountId());
                    if(mounts.size()!=1){uncertain(id,"TRASHED");return false;}Path physical=requireManagedRoot(Path.of(mounts.getFirst().physicalRoot()));
                    DrivePath root=DrivePath.of(row.root());Path visible=physical.resolve(root.relative()).normalize();
                    if(!visible.startsWith(physical))throw denied();
                    var store=new LocalFileStore(visible,MAX_ENTRIES,MAX_TRANSFER_BYTES);
                    var attr=Files.readAttributes(visible,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!row.scope().equals(digest(physical+"|"+root.value()+"|"+attr.fileKey()+"|"+attr.creationTime())))throw denied();
                    // Commit the intent before touching disk, so restart never mistakes a partial purge for retryable work.
                    jdbc.update("UPDATE sc_drive_trash SET status='PURGING',updated_at=? WHERE id=?",clock.millis(),id);return true;
                }));
                if(Boolean.TRUE.equals(done)){
                    TrashRow row=snapshot.getFirst();String physical=jdbc.queryForObject("SELECT physical_root FROM sc_drive_mount WHERE id=?",String.class,row.mountId());
                    Path mountRoot=requireManagedRoot(Path.of(physical));Path visible=mountRoot.resolve(DrivePath.of(row.root()).relative());
                    var attr=Files.readAttributes(visible,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!row.scope().equals(digest(mountRoot+"|"+row.root()+"|"+attr.fileKey()+"|"+attr.creationTime())))throw denied();
                    new LocalFileStore(visible,MAX_ENTRIES,MAX_TRANSFER_BYTES).purgeRecycled(id);
                    jdbc.update("UPDATE sc_drive_trash SET status='PURGED',updated_at=? WHERE id=? AND status='PURGING'",clock.millis(),id);
                    cleaned++;
                }
            }catch(IOException|RuntimeException failed){uncertain(id,"PURGING");uncertain(id,"TRASHED");}
        }return cleaned;
    }
    private void requireLifecycle(){if(catalog==null)throw fault("TRASH_UNAVAILABLE",501,"回收站尚未启用");}
    private TrashRow ownedTrash(Access a,String id,boolean lock)throws IOException{
        if(id==null||!id.matches("[a-f0-9]{32}"))throw fault("TRASH_NOT_FOUND",404,"回收记录不存在");
        var rows=jdbc.query("SELECT * FROM sc_drive_trash WHERE id=? AND actor_id=? AND mount_id=? AND scope_hash=?"+(lock?" FOR UPDATE":""),(r,n)->trashRow(r),id,a.stamp().userId(),a.stamp().mountId(),vaultScope(a));
        if(rows.size()!=1||!Objects.equals(rows.getFirst().workspaceId(),a.stamp().workspaceId()))throw fault("TRASH_NOT_FOUND",404,"回收记录不存在");return rows.getFirst();
    }
    private static TrashRow trashRow(ResultSet r)throws SQLException{
        return new TrashRow(new TrashView(r.getString("id"),r.getString("file_name"),r.getString("original_path"),r.getBoolean("is_directory"),r.getLong("byte_size"),r.getString("status"),r.getLong("created_at"),r.getLong("expires_at")),r.getLong("actor_id"),r.getObject("workspace_id",Long.class),r.getLong("mount_id"),r.getString("scope_hash"),r.getString("grant_root"));
    }

    private static Path visibleRoot(Access access) {
        return access.stamp().physical().resolve(DrivePath.of(access.stamp().root()).relative());
    }

    @FunctionalInterface private interface Mutation<T> { T run(Access access) throws IOException; }

    private Access access(Actor supplied, String key, Set<String> operations, boolean mutation) throws IOException {
        Actor actor = currentActor(supplied);
        List<MountRow> mounts = jdbc.query("SELECT " + COLUMNS + " FROM sc_drive_mount m WHERE m.mount_key=?", this::mount, requiredKey(key));
        if (mounts.size() != 1) throw denied();
        MountRow mount = mounts.getFirst();
        if (!mount.enabled() || mount.revision() < 1) throw denied();
        if (!"LOCAL".equals(mount.engine())) throw fault("DRIVE_ENGINE_UNAVAILABLE", 501, "当前独立后端尚未实现此存储协议");
        Grant grant = actor.role() == Role.ADMIN ? new Grant("/", KNOWN, null, 1) : requireGrant(actor, mount);
        if (!grant.permissions().containsAll(operations)) throw denied();
        Path physical = requireManagedRoot(Path.of(mount.physicalRoot()));
        DrivePath rootPath = DrivePath.of(grant.root());
        Path visible = physical.resolve(rootPath.relative()).normalize();
        if (!visible.startsWith(physical)) throw denied();
        LocalFileStore store = new LocalFileStore(visible, MAX_ENTRIES, MAX_TRANSFER_BYTES);
        return new Access(store, new Stamp(actor.id(), actor.role(), actor.workspaceId(), mount.id(), mount.revision(),
                physical, grant.root(), grant.permissions(), grant.quotaBytes(), grant.revision()));
    }

    private Grant requireGrant(Actor actor, MountRow mount) {
        if (actor.workspaceId() == null || !Objects.equals(actor.workspaceId(), mount.workspaceId())) throw denied();
        List<Grant> rows = jdbc.query("SELECT root_path,permissions,quota_bytes,enabled,revision FROM sc_drive_grant WHERE user_id=? AND mount_id=?",
                (result, index) -> {
                    if (!result.getBoolean("enabled") || result.getLong("revision") < 1) throw denied();
                    String root = DrivePath.of(result.getString("root_path")).value();
                    Set<String> permissions = permissions(result.getString("permissions"));
                    Long quota = result.getObject("quota_bytes", Long.class);
                    if (quota != null && quota < 0) throw denied();
                    return new Grant(root, permissions, quota, result.getLong("revision"));
                }, actor.id(), mount.id());
        if (rows.size() != 1) throw denied();
        Grant grant = rows.getFirst();
        if (actor.role() == Role.USER && !grant.root().equals("/members/u" + actor.id())) throw denied();
        if (actor.role() == Role.TENANT_ADMIN && !grant.root().equals("/admins/u" + actor.id())) throw denied();
        return grant;
    }

    private Actor currentActor(Actor supplied) {
        return accounts.current(supplied);
    }

    private Path configuredRoot() throws IOException {
        if (managedRoot == null) throw fault("DRIVE_NOT_CONFIGURED", 503, "尚未配置独立后端受控存储根目录");
        // Constructor validates every existing ancestor as well as the managed directory itself.
        new LocalFileStore(managedRoot, MAX_ENTRIES, MAX_TRANSFER_BYTES);
        return managedRoot.toRealPath();
    }

    private Path requireManagedRoot(Path root) throws IOException {
        Path managed = configuredRoot();
        if (root == null) throw fault("DRIVE_INVALID", 400, "请选择受控范围内已有的目录");
        Path candidate = root.toAbsolutePath().normalize();
        if (!candidate.startsWith(managed)) throw denied();
        if (candidate.equals(managed))
            throw fault("DRIVE_INVALID", 400, "请在受控根目录下为每个云盘准备独立子目录");
        new LocalFileStore(candidate, MAX_ENTRIES, MAX_TRANSFER_BYTES);
        Path canonical = candidate.toRealPath();
        if (!canonical.startsWith(managed) || !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) throw denied();
        return canonical;
    }

    /** Called under the singleton allocation lock, before publishing any new connection. */
    private void requireDisjointMount(Path candidate) {
        List<String> existing = jdbc.queryForList("SELECT physical_root FROM sc_drive_mount WHERE engine='LOCAL' LIMIT 201", String.class);
        if (existing.size() >= 200) throw fault("DRIVE_BUDGET", 409, "本地云盘数量已达到当前预算");
        for (String value : existing) {
            Path allocated = Path.of(value).toAbsolutePath().normalize();
            if (candidate.startsWith(allocated) || allocated.startsWith(candidate))
                throw fault("DRIVE_CONFLICT", 409, "此目录与现有云盘重叠，请使用互不包含的独立目录");
        }
    }

    private MountRow mount(ResultSet result, int index) throws SQLException {
        return new MountRow(result.getLong("id"), result.getString("mount_key"), result.getString("name"),
                result.getObject("workspace_id", Long.class), result.getLong("owner_id"), result.getString("engine"),
                result.getString("physical_root"), result.getBoolean("enabled"), result.getLong("revision"));
    }

    private static Set<String> permissions(String raw) {
        if (raw == null || raw.length() > 1024) throw denied();
        if (raw.isBlank()) return Set.of();
        Set<String> values = new LinkedHashSet<>(Arrays.asList(raw.split(",", -1)));
        if (!KNOWN.containsAll(values)) throw denied();
        return Set.copyOf(values);
    }

    private static String requiredKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9_-]{2,63}"))
            throw fault("DRIVE_INVALID", 400, "连接标识须为3至64位小写字母、数字、横线或下划线");
        return key;
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 120 || value.chars().anyMatch(Character::isISOControl))
            throw fault("DRIVE_INVALID", 400, "请填写不超过120字的存储名称");
        return value.trim();
    }

    private static <T> T protect(Work<T> action) {
        try { return action.run(); }
        catch (ApplicationFault safe) { throw safe; }
        catch (FileAlreadyExistsException conflict) { throw fault("DRIVE_CONFLICT", 409, "目标已存在同名文件，不会覆盖"); }
        catch (NoSuchFileException missing) { throw fault("DRIVE_NOT_FOUND", 404, "文件或目录不存在"); }
        catch (IllegalArgumentException invalid) { throw fault("DRIVE_INVALID", 400, "路径、连接或操作参数无效"); }
        catch (DataAccessException unavailable) { throw fault("DRIVE_DATABASE_UNAVAILABLE", 503, "暂时无法核验存储授权，请稍后重试"); }
        catch (IOException unavailable) { throw fault("DRIVE_IO_FAILED", 409, "文件操作未完成，请检查目录、大小预算和存储状态"); }
    }

    private static ApplicationFault denied() { return fault("DRIVE_FORBIDDEN", 403, "当前账号无权访问此连接或执行此操作"); }
    private static ApplicationFault fault(String code, int status, String message) { return new ApplicationFault(code, status, message); }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private record Grant(String root, Set<String> permissions, Long quotaBytes, long revision) { }
    private record Access(LocalFileStore store, Stamp stamp) { }
    private record Stamp(long userId, Role role, Long workspaceId, long mountId, long mountRevision, Path physical,
                         String root, Set<String> permissions, Long quota, long grantRevision) { }
    private record MountRow(long id, String key, String name, Long workspaceId, long ownerId, String engine,
                            String physicalRoot, boolean enabled, long revision) {
        MountView view(Set<String> permissions) { return new MountView(id, key, name, workspaceId, ownerId, engine, enabled, revision, permissions); }
    }
}
