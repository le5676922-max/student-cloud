package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.LocalFileStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Node-bound durable chunks. Publication uncertainty is a durable terminal barrier to replay. */
public final class ResumableUploadService {
    public static final int CHUNK_SIZE=4*1024*1024;
    public static final long TTL=86_400_000L;
    private static final long ACTOR_BYTES=100L*1024*1024, GLOBAL_BYTES=1024L*1024*1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DriveOperations mounts;
    private final Path staging;
    private final String rootIdentity,node;
    // Striping bounds memory and enforces at most two requests for every actor, even with many identities.
    private final Semaphore[] actors=new Semaphore[128];
    private final Semaphore activeIo=new Semaphore(4);
    private String cleanupCursor="";

    public record View(String id,String state,long size,int chunkSize,long expiresAt,
                       List<Integer> receivedChunks,List<Integer> missingChunks,String message) { }
    private record Row(String id,long owner,Long workspace,String node,String key,DrivePath target,long size,
                       String manifest,String authorization,String identity,String state,String attempt,long attemptUntil,long expires) { }
    private record Chunk(int index,String hash,String file) { }

    public ResumableUploadService(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock,DriveOperations mounts,
                                  Path staging,Path managedRoot,Path courseRoot,String node) {
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(transactions);this.tx.setTimeout(20);
        this.clock=Objects.requireNonNull(clock);this.mounts=Objects.requireNonNull(mounts);
        if(node==null||!node.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("Configure a stable content node id");
        this.node=node;
        for(int i=0;i<actors.length;i++)actors[i]=new Semaphore(2);
        if(staging==null){this.staging=null;this.rootIdentity=null;return;}
        try {
            Path configured=staging.toAbsolutePath().normalize();
            new LocalFileStore(configured,1000,MountService.MAX_TRANSFER_BYTES);
            this.staging=configured.toRealPath();
            for(Path other:new Path[]{managedRoot,courseRoot})if(other!=null){
                Path canonical=other.toAbsolutePath().normalize();
                new LocalFileStore(canonical,1000,MountService.MAX_TRANSFER_BYTES);
                canonical=canonical.toRealPath();
                if(this.staging.startsWith(canonical)||canonical.startsWith(this.staging))
                    throw new IllegalArgumentException("Upload staging must be separate from drive and course storage");
            }
            this.rootIdentity=identity(this.staging);
        }catch(IOException invalid){throw new IllegalArgumentException("Upload staging must be an existing real private directory",invalid);}
    }

    public Map<String,Object> capabilities(Actor actor,String key,DrivePath target,long size){
        mounts.uploadAuthorization(actor,key,target,size);
        if(staging==null)return Map.of("enabled",false,"protocol","independent-v1");
        checkedRoot();
        // Capability checks do not publish or claim that an existing target can be overwritten.
        return Map.of("enabled",true,"protocol","independent-v1","chunkSize",CHUNK_SIZE,
                "maxFileSize",MountService.MAX_TRANSFER_BYTES,"expiresInSeconds",86400);
    }

    public View init(Actor actor,String key,DrivePath target,long size,List<String> hashes,String manifest){
        return budget(actor,()->{
            checkedRoot();validateManifest(size,hashes,manifest);
            String authorization=mounts.uploadAuthorization(actor,key,target,size);
            cleanupExpired();
            // A pending publication must stay resumable even if the target may already exist.
            // Remote identity calls and the bounded quota walk happen before taking the global SQL budget lock.
            boolean preflight=!candidates(actor,key,size,manifest,false).stream().anyMatch(prior->
                    matches(prior,actor,target,authorization)&&prior.state().equals("COMPLETING"));
            if(preflight)mounts.prepareUpload(actor,key,target,size);
            Row selected=tx.execute(status->{
                // Also serializes actor/global byte reservation across service processes.
                jdbc.queryForObject("SELECT id FROM sc_content_settings WHERE id=1 FOR UPDATE",Integer.class);
                for(Row prior:candidates(actor,key,size,manifest,true))if(matches(prior,actor,target,authorization)){
                    if(!preflight&&prior.state().equals("OPEN"))throw initializationChanged();
                    return prior;
                }
                if(!preflight)throw initializationChanged();
                Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session WHERE owner_id=? AND state IN ('OPEN','COMPLETING')",Long.class,actor.id());
                Long bytes=jdbc.queryForObject("SELECT COALESCE(SUM(staging_reserved),0) FROM sc_upload_session WHERE owner_id=?",Long.class,actor.id());
                Long all=jdbc.queryForObject("SELECT COALESCE(SUM(staging_reserved),0) FROM sc_upload_session",Long.class);
                Long records=jdbc.queryForObject("SELECT COUNT(*) FROM sc_upload_session",Long.class);
                if(count>=3||size>ACTOR_BYTES-bytes||size>GLOBAL_BYTES-all||records>=1000)throw fault(429,"UPLOAD_BUDGET","未完成上传已达到数量或暂存预算，请完成或取消已有任务");
                String id=UUID.randomUUID().toString();Path folder=staging.resolve(id);
                try {
                    Files.createDirectory(folder);
                    String identity=identity(folder);
                    jdbc.update("INSERT INTO sc_upload_session(id,owner_id,workspace_id,node_id,mount_key,target_path,byte_size,staging_reserved,manifest_hash,authorization_hash,staging_identity,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?)",
                            id,actor.id(),actor.workspaceId(),node,key,target.value(),size,size,manifest,authorization,identity,clock.millis(),clock.millis()+TTL);
                    for(int index=0;index<hashes.size();index++)jdbc.update("INSERT INTO sc_upload_chunk(session_id,chunk_index,expected_hash) VALUES(?,?,?)",id,index,hashes.get(index));
                    return find(id);
                }catch(IOException failure){throw ioFault();}
            });
            authorize(actor,selected);
            return view(selected);
        });
    }

    private List<Row> candidates(Actor actor,String key,long size,String manifest,boolean lock){
        return jdbc.query("SELECT * FROM sc_upload_session WHERE owner_id=? AND node_id=? AND mount_key=? AND manifest_hash=? AND byte_size=? AND expires_at>? AND state IN ('OPEN','COMPLETING') ORDER BY created_at DESC LIMIT 4"+(lock?" FOR UPDATE":""),this::row,
                actor.id(),node,key,manifest,size,clock.millis());
    }
    private static boolean matches(Row prior,Actor actor,DrivePath target,String authorization){
        return prior.target().equals(target)&&Objects.equals(prior.workspace(),actor.workspaceId())&&prior.authorization().equals(authorization);
    }
    private static ApplicationFault initializationChanged(){return fault(409,"UPLOAD_STATE_CHANGED","上传状态已变化，请重新检查任务后重试");}

    public View status(Actor actor,String id){return budget(actor,()->{Row row=owned(actor,id);authorize(actor,row);return view(row);});}

    public View chunk(Actor actor,String id,int index,String suppliedHash,InputStream input){
        return budget(actor,()->{
            Row row=owned(actor,id);authorize(actor,row);requireOpen(row);
            List<Chunk> chunks=chunks(id);
            if(index<0||index>=chunks.size()||suppliedHash==null||!suppliedHash.equals(chunks.get(index).hash()))throw invalid();
            if(chunks.get(index).file()!=null){verifyChunk(row,chunks.get(index));return view(row);}
            String token=claim(row);Path output=null;
            try {
                pruneUnreferenced(row);
                output=folder(row).resolve(token+".part");
                long expected=chunkBytes(row,index);MessageDigest digest=digest();
                try(OutputStream ready=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    copy(input,ready,expected,digest);
                }
                if(!HexFormat.of().formatHex(digest.digest()).equals(suppliedHash))throw invalid();
                authorize(actor,row);
                Path written=output;
                tx.execute(status->{
                    Row current=locked(id);ensureAttempt(current,token);
                    jdbc.update("UPDATE sc_upload_chunk SET file_name=? WHERE session_id=? AND chunk_index=? AND file_name IS NULL",written.getFileName().toString(),id,index);
                    release(id,token);return null;
                });
                output=null;return view(find(id));
            }catch(IOException failure){throw ioFault();}
            finally{if(output!=null)deleteOwned(output);release(id,token);}
        });
    }

    public View complete(Actor actor,String id){
        return budget(actor,()->{
            Row row=owned(actor,id);authorize(actor,row);
            if(row.state().equals("COMPLETED")||row.state().equals("COMPLETING"))return view(row);
            requireOpen(row);
            var chunks=chunks(id);if(chunks.stream().anyMatch(chunk->chunk.file()==null))throw fault(409,"UPLOAD_INCOMPLETE","上传尚有缺失分片，请继续上传");
            String token=claim(row);Path assembled=null;
            try {
                pruneUnreferenced(row);
                assembled=folder(row).resolve(token+".assembled");
                try(OutputStream ready=Files.newOutputStream(assembled,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    for(Chunk chunk:chunks){
                        Path source=verifyChunk(row,chunk);
                        MessageDigest copied=digest();
                        try(InputStream data=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){copy(data,ready,chunkBytes(row,chunk.index()),copied);}
                        if(!chunk.hash().equals(HexFormat.of().formatHex(copied.digest())))throw fault(409,"UPLOAD_CORRUPT","分片在合并时发生变化，请重新上传");
                    }
                }
                authorize(actor,row);mounts.prepareUpload(actor,row.key(),row.target(),row.size());
                // Commit BEFORE entering the file publication window. Neither timeout nor process restart replays it.
                tx.execute(status->{Row current=locked(id);ensureAttempt(current,token);
                    jdbc.update("UPDATE sc_upload_session SET state='COMPLETING',attempt_token=NULL,attempt_until=0 WHERE id=? AND attempt_token=?",id,token);return null;});
                try(InputStream data=Files.newInputStream(assembled,LinkOption.NOFOLLOW_LINKS)){
                    mounts.upload(actor,row.key(),row.target(),data,row.size());
                }
                jdbc.update("UPDATE sc_upload_session SET state='COMPLETED' WHERE id=? AND state='COMPLETING'",id);
                // The durable receipt precedes cleanup; retained bytes stay reserved after a crash.
                clearFolder(row);
                jdbc.update("UPDATE sc_upload_session SET staging_reserved=0 WHERE id=? AND state='COMPLETED'",id);
                return view(find(id));
            }catch(IOException failure){throw ioFault();}
            finally{if(assembled!=null)deleteOwned(assembled);release(id,token);}
        });
    }

    /** Cancellation drops private chunks only; an uncertain publication and any target are preserved. */
    public View cancel(Actor actor,String id){
        return budget(actor,()->{
            Row row=owned(actor,id);authorize(actor,row);
            if(row.state().equals("COMPLETING"))throw fault(409,"UPLOAD_PUBLICATION_PENDING","保存结果待核查，请检查目标文件，不要重复提交");
            if(row.state().equals("COMPLETED")||row.state().equals("EXPIRED"))return view(row);
            String token=claim(row);
            try{clearFolder(row);jdbc.update("UPDATE sc_upload_session SET state='EXPIRED',staging_reserved=0,attempt_token=NULL,attempt_until=0 WHERE id=? AND attempt_token=?",id,token);return view(find(id));}
            finally{release(id,token);}
        });
    }

    /** Bounded sweep of this node's expired records; no target directory is ever deleted. */
    public synchronized void cleanupExpired(){
        if(staging==null)return;
        checkedRoot();
        var completed=jdbc.query("SELECT * FROM sc_upload_session WHERE node_id=? AND state='COMPLETED' AND staging_reserved>0 ORDER BY created_at LIMIT 16",this::row,node);
        for(Row row:completed)tx.execute(status->{Row current=locked(row.id());if(!current.state().equals("COMPLETED"))return null;
            clearFolder(current);jdbc.update("UPDATE sc_upload_session SET staging_reserved=0 WHERE id=?",row.id());return null;});
        var expired=jdbc.query("SELECT * FROM sc_upload_session WHERE node_id=? AND id>? AND expires_at<=? AND (attempt_token IS NULL OR attempt_until<?) ORDER BY id LIMIT 32",this::row,node,cleanupCursor,clock.millis(),clock.millis());
        if(expired.isEmpty())cleanupCursor="";
        for(Row row:expired){
            cleanupCursor=row.id();
            // Hold the same record lock as claims while deleting. A later request cannot take over mid-cleanup.
            try{tx.execute(status->{Row current=locked(row.id());if(current.expires()>clock.millis()||current.attemptUntil()>clock.millis())return null;
                Path directory=checkedRoot().resolve(current.id());
                if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){
                    clearFolder(current);
                    try{Files.delete(directory);}catch(IOException failure){throw ioFault();}
                }
                jdbc.update("DELETE FROM sc_upload_chunk WHERE session_id=?",row.id());
                jdbc.update("DELETE FROM sc_upload_session WHERE id=?",row.id());return null;});}
            catch(ApplicationFault preserved){/* Keep both reservation and record for investigation; continue the bounded sweep. */}
        }
    }

    private Row owned(Actor actor,String id){
        checkedRoot();if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid();
        Row row=find(id);if(row.owner()!=actor.id()||!Objects.equals(row.workspace(),actor.workspaceId()))throw fault(404,"UPLOAD_NOT_FOUND","上传任务不存在");
        if(!row.node().equals(node))throw fault(409,"UPLOAD_NODE_CHANGED","此任务属于另一内容节点，请返回原节点续传");
        if(row.expires()<=clock.millis())throw fault(410,"UPLOAD_EXPIRED","上传任务已过期，请重新选择文件");
        return row;
    }
    private void authorize(Actor actor,Row row){
        String actual=mounts.uploadAuthorization(actor,row.key(),row.target(),row.size());
        if(!row.authorization().equals(actual))throw fault(409,"UPLOAD_AUTHORIZATION_CHANGED","目录或授权已变化，请重新创建上传任务");
        folder(row);
    }
    private View view(Row row){
        var chunks=chunks(row.id());var received=new ArrayList<Integer>();var missing=new ArrayList<Integer>();
        for(Chunk chunk:chunks)if(chunk.file()==null)missing.add(chunk.index());else received.add(chunk.index());
        return new View(row.id(),row.state(),row.size(),CHUNK_SIZE,row.expires(),List.copyOf(received),List.copyOf(missing),
                row.state().equals("COMPLETING")?"保存结果待核查，请检查目标目录，不要重复提交":row.state());
    }
    private String claim(Row row){
        String token=UUID.randomUUID().toString();
        int changed=jdbc.update("UPDATE sc_upload_session SET attempt_token=?,attempt_until=? WHERE id=? AND state=? AND (attempt_token IS NULL OR attempt_until<?)",
                token,clock.millis()+180_000,row.id(),row.state(),clock.millis());
        if(changed!=1)throw fault(409,"UPLOAD_BUSY","上传任务正在处理，请稍后检查状态");return token;
    }
    private void ensureAttempt(Row row,String token){if(!token.equals(row.attempt())||!row.state().equals("OPEN"))throw fault(409,"UPLOAD_ATTEMPT_CHANGED","上传处理已变化，请重新检查任务状态");}
    private void release(String id,String token){jdbc.update("UPDATE sc_upload_session SET attempt_token=NULL,attempt_until=0 WHERE id=? AND attempt_token=?",id,token);}
    private Row locked(String id){return jdbc.queryForObject("SELECT * FROM sc_upload_session WHERE id=? FOR UPDATE",this::row,id);}
    private Row find(String id){var found=jdbc.query("SELECT * FROM sc_upload_session WHERE id=?",this::row,id);if(found.size()!=1)throw fault(404,"UPLOAD_NOT_FOUND","上传任务不存在");return found.getFirst();}
    private Row row(java.sql.ResultSet r,int index)throws java.sql.SQLException{return new Row(r.getString("id"),r.getLong("owner_id"),r.getObject("workspace_id",Long.class),r.getString("node_id"),r.getString("mount_key"),DrivePath.of(r.getString("target_path")),r.getLong("byte_size"),r.getString("manifest_hash"),r.getString("authorization_hash"),r.getString("staging_identity"),r.getString("state"),r.getString("attempt_token"),r.getLong("attempt_until"),r.getLong("expires_at"));}
    private List<Chunk> chunks(String id){return jdbc.query("SELECT chunk_index,expected_hash,file_name FROM sc_upload_chunk WHERE session_id=? ORDER BY chunk_index",(r,i)->new Chunk(r.getInt(1),r.getString(2),r.getString(3)),id);}
    private Path checkedRoot(){
        if(staging==null)throw fault(503,"UPLOAD_NOT_CONFIGURED","尚未配置私有上传暂存目录");
        try{new LocalFileStore(staging,1000,MountService.MAX_TRANSFER_BYTES);if(!rootIdentity.equals(identity(staging)))throw ioFault();return staging;}
        catch(IOException invalid){throw ioFault();}
    }
    private Path folder(Row row){try{
        Path directory=checkedRoot().resolve(row.id());new LocalFileStore(directory,64,MountService.MAX_TRANSFER_BYTES);
        if(!row.identity().equals(identity(directory)))throw ioFault();return directory;
    }catch(IOException invalid){throw ioFault();}}
    private Path verifyChunk(Row row,Chunk chunk){
        if(chunk.file()==null||!chunk.file().matches("[a-f0-9-]{36}\\.part"))throw ioFault();
        Path path=folder(row).resolve(chunk.file());
        try{
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||Files.size(path)!=chunkBytes(row,chunk.index()))throw ioFault();
            MessageDigest digest=digest();try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){copy(input,OutputStream.nullOutputStream(),chunkBytes(row,chunk.index()),digest);}
            if(!HexFormat.of().formatHex(digest.digest()).equals(chunk.hash()))throw fault(409,"UPLOAD_CORRUPT","已保存分片校验失败，请取消后重新创建任务");
            return path;
        }catch(IOException failure){throw ioFault();}
    }
    private void clearFolder(Row row){
        Path directory=folder(row);
        try(var children=Files.newDirectoryStream(directory)){
            int count=0;for(Path child:children){
                if(++count>256||!child.getFileName().toString().matches("[a-f0-9-]{36}\\.(part|assembled)")||!Files.isRegularFile(child,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(child))throw ioFault();
                Files.delete(child);
            }
            // Keep the empty identity-bound directory until the durable record is removed.
        }catch(IOException failure){throw ioFault();}
    }
    /** Recover attempt files left by process death before their chunk receipt was committed. */
    private void pruneUnreferenced(Row row){
        Set<String> retained=new HashSet<>();for(Chunk chunk:chunks(row.id()))if(chunk.file()!=null)retained.add(chunk.file());
        try(var files=Files.newDirectoryStream(folder(row))){
            int count=0;for(Path file:files){
                String name=file.getFileName().toString();
                if(++count>256||!name.matches("[a-f0-9-]{36}\\.(part|assembled)")||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw ioFault();
                if(!retained.contains(name))Files.delete(file);
            }
        }catch(IOException failure){throw ioFault();}
    }
    private static String identity(Path path)throws IOException{var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.creationTime().toMillis()<=0)throw new IOException("Missing directory identity");return sha256((path.toRealPath()+"|"+a.fileKey()+"|"+a.creationTime()).getBytes(StandardCharsets.UTF_8));}
    private static void deleteOwned(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){/* Expiry sweep retries this private attempt file. */}}
    private long chunkBytes(Row row,int index){return Math.min(CHUNK_SIZE,row.size()-(long)index*CHUNK_SIZE);}
    private static void copy(InputStream input,OutputStream output,long expected,MessageDigest digest)throws IOException{
        byte[] buffer=new byte[16*1024];long total=0,started=System.nanoTime();int empty=0;
        for(int count;(count=input.read(buffer))!=-1;){if(System.nanoTime()-started>120_000_000_000L)throw fault(408,"UPLOAD_TIMEOUT","分片处理超时，请稍后续传");if(count==0){if(++empty>1024)throw invalid();continue;}empty=0;total+=count;if(total>expected)throw invalid();digest.update(buffer,0,count);output.write(buffer,0,count);}
        if(total!=expected)throw invalid();
    }
    private static void validateManifest(long size,List<String> hashes,String manifest){
        if(size<=0||size>MountService.MAX_TRANSFER_BYTES||hashes==null||hashes.size()!=(size+CHUNK_SIZE-1)/CHUNK_SIZE||hashes.stream().anyMatch(h->h==null||!h.matches("[a-f0-9]{64}"))||!manifestHash(hashes).equals(manifest))throw invalid();
    }
    public static String manifestHash(List<String> hashes){return sha256(String.join("\n",hashes).getBytes(StandardCharsets.US_ASCII));}
    public static String sha256(byte[] data){return HexFormat.of().formatHex(digest().digest(data));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void requireOpen(Row row){if(!row.state().equals("OPEN"))throw fault(409,"UPLOAD_STATE","当前任务不能再接收分片");}
    private <T>T budget(Actor actor,java.util.function.Supplier<T> action){
        Semaphore gate=actors[Math.floorMod(Long.hashCode(actor.id()),actors.length)];if(!gate.tryAcquire())throw fault(429,"UPLOAD_BUSY","同时上传处理数量已达上限");
        if(!activeIo.tryAcquire()){gate.release();throw fault(429,"UPLOAD_BUSY","上传暂存繁忙，请稍后续传");}
        try{return action.get();}finally{activeIo.release();gate.release();}
    }
    private static ApplicationFault invalid(){return fault(400,"UPLOAD_INVALID","上传大小、分片或内容摘要无效");}
    private static ApplicationFault ioFault(){return fault(409,"UPLOAD_IO_FAILED","私有上传暂存状态异常，请检查任务后重试");}
    private static ApplicationFault fault(int status,String code,String message){return new ApplicationFault(code,status,message);}
}
