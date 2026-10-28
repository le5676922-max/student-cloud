package io.studentcloud.content.sharing;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.course.SharedCourseService;
import io.studentcloud.drive.*;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Content-owned grants. Opaque IDs identify a policy; they never replace session authorization. */
public final class ControlledShareService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final long DAY=86_400_000L;
    public static final long MAX_BYTES=50L*1024*1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final DriveOperations mounts;
    private final SharedCourseService courses;
    private final Path managedRoot;
    // Hold a permit through HTTP output so slow clients cannot retain unbounded temporary files.
    private final Semaphore ioSlots=new Semaphore(2);

    public ControlledShareService(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock,
            AccountDirectory accounts,DriveOperations mounts,SharedCourseService courses,Path managedRoot){
        this.jdbc=Objects.requireNonNull(jdbc);this.transactions=new TransactionTemplate(transactions);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);this.transactions.setTimeout(10);
        this.clock=Objects.requireNonNull(clock);this.accounts=Objects.requireNonNull(accounts);
        this.mounts=Objects.requireNonNull(mounts);this.courses=Objects.requireNonNull(courses);
        this.managedRoot=managedRoot==null?null:managedRoot.toAbsolutePath().normalize();
    }

    /** No mount key, source path, account identity, physical path, token or content URL leaves this view. */
    public record ShareView(String id,String name,long size,String audience,Long courseId,long expiresAt,
                            long createdAt,Long revokedAt,long version,boolean owner){}

    public ShareView create(Actor supplied,String mountKey,String sourcePath,String audience,Long courseId,Long expiresAt){
        Actor actor=participant(supplied);
        if(!Set.of("OWN_WORKSPACE","COURSE").contains(audience==null?"":audience)
                || "COURSE".equals(audience)!=(courseId!=null) || courseId!=null&&courseId<1)throw invalid("请选择本空间或已加入的课程作为访问范围");
        if(mountKey==null||!mountKey.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid("存储连接无效");
        DrivePath path=path(sourcePath);long now=clock.millis();long expiry=expiresAt==null?now+7*DAY:expiresAt;
        if(expiry<=now||expiry>now+30*DAY)throw invalid("分享有效期须在未来30天内");
        if(courseId!=null)courseMember(actor,courseId);
        // Read permission and parent capabilities are fresh; directories are deliberately not supported.
        if(!mounts.directoryCapabilities(actor,mountKey,path.parent()).contains("read"))throw denied();
        FileRecord file=mounts.stat(actor,mountKey,path).orElseThrow(ControlledShareService::changed);
        fileBudget(file);
        String hash=fingerprint(actor,mountKey,path,file,null);
        Actor current=participant(actor);
        if(!Objects.equals(current.workspaceId(),actor.workspaceId()))throw denied();
        if(courseId!=null)courseMember(current,courseId);
        byte[] random=new byte[32];RANDOM.nextBytes(random);String id=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Row row=new Row(id,current.id(),current.workspaceId(),mountKey,path.value(),file.name(),file.size(),file.modifiedAt().toEpochMilli(),hash,audience,courseId,expiry,now,null,1);
        // Only bounded local SQL runs while the budget lock is held; never network/filesystem IO.
        transactions.executeWithoutResult(status->{
            jdbc.update("INSERT INTO sc_share_owner_guard(account_id) VALUES(?) ON DUPLICATE KEY UPDATE account_id=account_id",current.id());
            jdbc.queryForObject("SELECT account_id FROM sc_share_owner_guard WHERE account_id=? FOR UPDATE",Long.class,current.id());
            if(count("SELECT COUNT(*) FROM sc_file_share WHERE owner_account=? AND revoked_at IS NULL AND expires_at>?",current.id(),clock.millis())>=100)
                throw fault("SHARE_QUOTA",409,"最多保留100个有效分享，请先撤销旧分享");
            if(count("SELECT COUNT(*) FROM sc_file_share WHERE owner_account=? AND created_at>=?",current.id(),clock.millis()-60_000)>=10)
                throw fault("SHARE_RATE_LIMIT",429,"分享创建过于频繁，请稍后重试");
            jdbc.update("INSERT INTO sc_file_share(share_id,owner_account,workspace_id,mount_key,source_path,file_name,file_size,modified_at,content_hash,audience,course_id,expires_at,created_at,revoked_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,1)",
                    id,row.owner(),row.workspace(),mountKey,path.value(),row.name(),row.size(),row.modified(),hash,audience,courseId,expiry,now);
        });
        return view(current,row);
    }

    public List<ShareView> list(Actor supplied,int offset,int limit){
        Actor actor=accounts.current(supplied);
        if(offset<0||offset>10_000||limit<1||limit>100)throw invalid("分享列表分页无效");
        // An owner can always reach every active grant within the first 100 rows, even after many revocations.
        return jdbc.query("SELECT * FROM sc_file_share WHERE owner_account=? ORDER BY CASE WHEN revoked_at IS NULL AND expires_at>? THEN 0 ELSE 1 END,created_at DESC,share_id LIMIT ? OFFSET ?",this::row,actor.id(),clock.millis(),limit,offset).stream().map(row->view(actor,row)).toList();
    }

    public ShareView get(Actor supplied,String id){
        Actor actor=accounts.current(supplied);Row row=load(id);Actor owner=authorize(actor,row);
        fingerprint(owner,row.mount(),path(row.path()),metadata(row),row.hash());
        // Revocation/expiry/account changes during IO invalidate this response.
        unchanged(row);authorize(accounts.current(actor),row);
        return view(actor,row);
    }

    public void revoke(Actor supplied,String id,long expectedVersion){
        Actor actor=accounts.current(supplied);Row row=load(id);
        if(actor.id()!=row.owner()&&actor.role()!=Role.ADMIN)throw denied();
        if(expectedVersion<1||jdbc.update("UPDATE sc_file_share SET revoked_at=?,version=version+1 WHERE share_id=? AND version=? AND revoked_at IS NULL",clock.millis(),row.id(),expectedVersion)!=1)
            throw fault("SHARE_CHANGED",409,"分享已被修改或撤销，请刷新后重试");
    }

    public Content open(Actor supplied,String id){
        Actor actor=accounts.current(supplied);Row row=load(id);Actor owner=authorize(actor,row);
        acquire();Path temporary=null;
        try{
            temporary=Files.createTempFile("student-cloud-share-",".tmp");
            if(managedRoot!=null&&temporary.toRealPath().startsWith(managedRoot.toRealPath()))
                throw fault("SHARE_STAGING_UNAVAILABLE",503,"分享暂存必须与可浏览存储隔离");
            try(OutputStream output=Files.newOutputStream(temporary)){copyAndHash(owner,row.mount(),path(row.path()),metadata(row),row.hash(),output);}
            unchanged(row);authorize(accounts.current(actor),row);
            InputStream input=Files.newInputStream(temporary);return new Content(row.name(),row.size(),input,temporary,ioSlots);
        }catch(IOException failure){cleanup(temporary);ioSlots.release();throw changed();}
        catch(RuntimeException failure){cleanup(temporary);ioSlots.release();throw failure;}
    }

    public static final class Content implements AutoCloseable {
        private final String name;private final long size;private final InputStream input;private final Path temporary;private final Semaphore permit;private boolean closed;
        private Content(String name,long size,InputStream input,Path temporary,Semaphore permit){this.name=name;this.size=size;this.input=input;this.temporary=temporary;this.permit=permit;}
        public String name(){return name;}public long size(){return size;}public InputStream input(){return input;}
        @Override public void close()throws IOException{if(closed)return;closed=true;try{input.close();}finally{try{Files.deleteIfExists(temporary);}finally{permit.release();}}}
    }

    private Actor authorize(Actor recipient,Row row){
        if(row.revoked()!=null||row.expiry()<=clock.millis())throw fault("SHARE_GONE",410,"分享已撤销或过期");
        Actor owner;
        try{owner=participant(accounts.account(row.owner()));}catch(ApplicationFault unavailable){throw denied();}
        if(!Objects.equals(owner.workspaceId(),row.workspace())||!accounts.workspaceEnabled(row.workspace()))throw denied();
        if("OWN_WORKSPACE".equals(row.audience())){
            if(recipient.workspaceId()==null||!Objects.equals(recipient.workspaceId(),row.workspace())||recipient.role()==Role.ADMIN)throw denied();
        }else{courseMember(owner,row.course());courseMember(recipient,row.course());}
        try{if(!mounts.directoryCapabilities(owner,row.mount(),path(row.path()).parent()).contains("read"))throw denied();}
        catch(ApplicationFault failed){if(failed.httpStatus()==404)throw changed();throw failed;}
        return owner;
    }
    private Actor participant(Actor supplied){Actor actor=accounts.current(supplied);if(actor.role()!=Role.USER&&actor.role()!=Role.TENANT_ADMIN||actor.workspaceId()==null||!accounts.workspaceEnabled(actor.workspaceId()))throw denied();return actor;}
    private void courseMember(Actor actor,long id){try{if(actor.role()==Role.ADMIN||!courses.get(actor,id).enabled())throw denied();}catch(ApplicationFault unavailable){throw denied();}}
    private String fingerprint(Actor owner,String mount,DrivePath path,FileRecord file,String expected){
        acquire();try{return copyAndHash(owner,mount,path,file,expected,OutputStream.nullOutputStream());}catch(IOException failed){throw changed();}finally{ioSlots.release();}
    }
    private String copyAndHash(Actor owner,String mount,DrivePath path,FileRecord expected,String expectedHash,OutputStream output)throws IOException{
        try(var handle=mounts.readWithMetadata(owner,mount,path)){
            FileRecord actual=handle.metadata();
            if(actual.directory()||actual.size()!=expected.size()||actual.modifiedAt().toEpochMilli()!=expected.modifiedAt().toEpochMilli())throw changed();
            fileBudget(actual);
            MessageDigest digest=digest();byte[] buffer=new byte[16*1024];long total=0;long started=System.nanoTime();int empty=0;
            for(int length;(length=handle.input().read(buffer))!=-1;){
                if(System.nanoTime()-started>60_000_000_000L)throw fault("SHARE_BUSY",408,"分享文件读取超时，请稍后重试");
                if(length==0){if(++empty>1024)throw changed();continue;}empty=0;total+=length;if(total>actual.size()||total>MAX_BYTES)throw changed();
                digest.update(buffer,0,length);output.write(buffer,0,length);
            }
            if(total!=actual.size())throw changed();String hash=HexFormat.of().formatHex(digest.digest());
            if(expectedHash!=null&&!MessageDigest.isEqual(expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw changed();
            return hash;
        }catch(ApplicationFault failed){if(failed.httpStatus()==404)throw changed();throw failed;}
    }
    private void unchanged(Row before){Row current=load(before.id());if(current.version()!=before.version()||current.revoked()!=null||current.expiry()<=clock.millis())throw fault("SHARE_GONE",410,"分享已撤销或过期");}
    private void acquire(){if(!ioSlots.tryAcquire())throw fault("SHARE_BUSY",429,"分享文件读取繁忙，请稍后重试");}
    private Row load(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{43}"))throw missing();List<Row> found=jdbc.query("SELECT * FROM sc_file_share WHERE share_id=?",this::row,id);if(found.size()!=1)throw missing();return found.getFirst();}
    private Row row(ResultSet r,int index)throws SQLException{return new Row(r.getString("share_id"),r.getLong("owner_account"),r.getLong("workspace_id"),r.getString("mount_key"),r.getString("source_path"),r.getString("file_name"),r.getLong("file_size"),r.getLong("modified_at"),r.getString("content_hash"),r.getString("audience"),r.getObject("course_id",Long.class),r.getLong("expires_at"),r.getLong("created_at"),r.getObject("revoked_at",Long.class),r.getLong("version"));}
    private static ShareView view(Actor actor,Row row){return new ShareView(row.id(),row.name(),row.size(),row.audience(),row.course(),row.expiry(),row.created(),row.revoked(),row.version(),actor.id()==row.owner());}
    private static FileRecord metadata(Row row){return new FileRecord(path(row.path()),row.name(),false,row.size(),java.time.Instant.ofEpochMilli(row.modified()));}
    private long count(String sql,Object...args){Long count=jdbc.queryForObject(sql,Long.class,args);return count==null?0:count;}
    private static DrivePath path(String value){if(value==null||value.length()>4096)throw invalid("文件路径无效");try{return DrivePath.of(value);}catch(IllegalArgumentException invalid){throw invalid("文件路径无效");}}
    private static void fileBudget(FileRecord file){if(file.directory())throw invalid("当前仅支持单个文件分享，请选择文件");if(file.size()<0||file.size()>MAX_BYTES)throw fault("SHARE_TOO_LARGE",413,"分享文件不能超过50MiB");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void cleanup(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){/* OS cleanup remains best effort after a failed read. */}}
    private static ApplicationFault changed(){return fault("SHARE_SOURCE_CHANGED",409,"源文件已变化、移动或删除，请让分享者重新创建分享");}
    private static ApplicationFault denied(){return fault("40300",403,"没有此分享的访问权限");}
    private static ApplicationFault missing(){return fault("40400",404,"分享不存在");}
    private static ApplicationFault invalid(String message){return fault("SHARE_INVALID",400,message);}
    private static ApplicationFault fault(String code,int status,String message){return new ApplicationFault(code,status,message);}
    private record Row(String id,long owner,long workspace,String mount,String path,String name,long size,long modified,String hash,String audience,Long course,long expiry,long created,Long revoked,long version){}
}
