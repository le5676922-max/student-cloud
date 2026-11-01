package io.studentcloud.drive.application;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable per-owner operation receipts. Never stores paths, request bodies, keys or exception text.
 * Each phase commits in REQUIRES_NEW before the next phase/storage callback starts. Fencing guards
 * SQL state only: adapters must independently prevent stale workers overwriting storage objects.
 * Unknown/in-progress receipts never expire, release capacity or replay storage automatically.
 */
public final class StorageOperationJournal {
    private static final Set<String> TYPES=Set.of("UPLOAD","MKDIR","COPY","MOVE","RENAME","DELETE","RESTORE","PURGE");
    private static final String COLUMNS="row_no,id,actor_id,workspace_id,mount_id,type,request_hash,state,result_ref,error_code,attempt,fence,revision,created_at,updated_at";
    private static final String SCOPE="actor_id=? AND mount_id=? AND (workspace_id=? OR (workspace_id IS NULL AND ? IS NULL))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate committed;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final DriveOperations drives;

    public StorageOperationJournal(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,AccountDirectory accounts,DriveOperations drives) {
        this.jdbc=Objects.requireNonNull(jdbc);this.clock=Objects.requireNonNull(clock);
        this.accounts=Objects.requireNonNull(accounts);this.drives=Objects.requireNonNull(drives);
        committed=new TransactionTemplate(Objects.requireNonNull(tx));
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);committed.setTimeout(15);
    }
    public record Receipt(String id,String type,String state,String resultRef,String errorCode,int attempt,long createdAt,long updatedAt) {}
    public record Page(List<Receipt> items,String nextCursor) {public Page {items=List.copyOf(items);}}
    /** Trusted in-process worker capability, never accepted from an HTTP request. */
    public record Lease(String id,long fence) {}
    public record Execution<T>(Receipt receipt,T value,boolean replayed) {}
    private record Scope(Actor actor,MountView mount) {}
    private record Row(long rowNo,String id,long actorId,Long workspaceId,long mountId,String type,String requestHash,String state,String resultRef,String errorCode,int attempt,long fence,long revision,long createdAt,long updatedAt) {
        Receipt receipt(){return new Receipt(id,type,state,resultRef,errorCode,attempt,createdAt,updatedAt);}
    }

    /** canonicalRequest must be assembled by the server and include all semantic inputs/content hash. */
    public Receipt prepare(Actor supplied,String key,String type,String idempotencyKey,String canonicalRequest) {
        Scope scope=authorize(supplied,key);if(!TYPES.contains(type))throw invalid();
        if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>200||canonicalRequest==null||canonicalRequest.length()>65536)throw invalid();
        String requestHash=sha256(canonicalRequest),keyHash=sha256(idempotencyKey);
        try {
            return committed.execute(status->{
                var existing=byKey(scope.actor().id(),type,keyHash);
                if(!existing.isEmpty())return same(existing.getFirst(),scope,requestHash);
                String id=UUID.randomUUID().toString();long now=clock.millis();
                jdbc.update("INSERT INTO sc_storage_operation(id,actor_id,workspace_id,mount_id,type,request_hash,idempotency_hash,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PREPARED',?,?)",
                    id,scope.actor().id(),scope.actor().workspaceId(),scope.mount().id(),type,requestHash,keyHash,now,now);
                event(id,1,"PREPARED",0,now);return row(id,false).receipt();
            });
        } catch(DuplicateKeyException race) {
            // The failed insert transaction must roll back before reading the concurrent winner.
            return committed.execute(status->same(byKey(scope.actor().id(),type,keyHash).getFirst(),scope,requestHash));
        }
    }
    public Receipt get(Actor actor,String key,String id) {
        Scope scope=authorize(actor,key);validId(id);
        var found=jdbc.query("SELECT "+COLUMNS+" FROM sc_storage_operation WHERE id=? AND "+SCOPE,this::map,id,scope.actor().id(),scope.mount().id(),scope.actor().workspaceId(),scope.actor().workspaceId());
        if(found.isEmpty())throw missing();return found.getFirst().receipt();
    }
    public Page list(Actor actor,String key,String cursor,int limit) {
        Scope scope=authorize(actor,key);if(limit<1||limit>50)throw invalid();
        long before=Long.MAX_VALUE;
        if(cursor!=null&&!cursor.isBlank()) {
            if(!cursor.matches("[1-9][0-9]{0,18}"))throw invalid();
            try {before=Long.parseLong(cursor);}catch(NumberFormatException invalid){throw invalid();}
        }
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM sc_storage_operation WHERE "+SCOPE+" AND row_no<? ORDER BY row_no DESC LIMIT ?",this::map,
            scope.actor().id(),scope.mount().id(),scope.actor().workspaceId(),scope.actor().workspaceId(),before,limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(rows.size(),limit));
        return new Page(page.stream().map(Row::receipt).toList(),more?Long.toString(page.getLast().rowNo()):null);
    }
    public Lease begin(String id) {
        validId(id);
        return committed.execute(status->{
            Row row=row(id,true);if(!row.state().equals("PREPARED"))throw conflict();
            long fence=Math.addExact(row.fence(),1),now=clock.millis();
            jdbc.update("UPDATE sc_storage_operation SET state='RUNNING',fence=?,attempt=attempt+1,revision=revision+1,updated_at=? WHERE id=?",fence,now,id);
            event(id,row.revision()+1,"RUNNING",fence,now);return new Lease(id,fence);
        });
    }
    /** Call before any possible publication, not after a filesystem/object side effect. */
    public Receipt committing(Lease lease){return transition(lease,Set.of("RUNNING"),"COMMITTING",null,null);}
    /** Only call with definitive publication evidence. A null reference means no content/version identity is available. */
    public Receipt succeeded(Lease lease,UUID resultRef){return transition(lease,Set.of("COMMITTING"),"SUCCEEDED",resultRef==null?null:resultRef.toString(),null);}
    public Receipt indeterminate(Lease lease){return transition(lease,Set.of("RUNNING","COMMITTING"),"INDETERMINATE",null,"OUTCOME_UNKNOWN");}
    public Receipt cancel(Actor actor,String key,String id) {
        get(actor,key,id); // reauthorize; only PREPARED proves this journal never started storage
        return transition(new Lease(id,0),Set.of("PREPARED"),"CANCELLED",null,null);
    }

    /**
     * Optional adapter boundary for synchronous, definitively-confirmed storage operations only.
     * A normal callback return must prove the requested effect, not merely accept/queue a job.
     * On error the effect may already exist: retain INDETERMINATE and rethrow, never rerun/undo.
     * Replays intentionally have no value; callers must return the receipt, not fabricate file data.
     */
    public <T> Execution<T> perform(Actor actor,String key,String type,String idempotencyKey,String request,Supplier<T> mutation) {
        Receipt prepared=prepare(actor,key,type,idempotencyKey,request);
        if(!prepared.state().equals("PREPARED"))return new Execution<>(prepared,null,true);
        Lease lease;
        try {lease=begin(prepared.id());}
        catch(ApplicationFault racing) {if(!racing.code().equals("OPERATION_CONFLICT"))throw racing;return new Execution<>(get(actor,key,prepared.id()),null,true);}
        committing(lease);
        try {
            T value=mutation.get();
            return new Execution<>(succeeded(lease,null),value,false);
        } catch(RuntimeException|Error failure) {
            try {indeterminate(lease);}catch(RuntimeException evidenceFailure){failure.addSuppressed(evidenceFailure);}
            throw failure;
        }
    }
    private Receipt transition(Lease lease,Set<String> from,String to,String reference,String error) {
        Objects.requireNonNull(lease);validId(lease.id());
        return committed.execute(status->{
            Row row=row(lease.id(),true);
            if(row.fence()!=lease.fence()||!from.contains(row.state()))throw conflict();
            long now=clock.millis();
            jdbc.update("UPDATE sc_storage_operation SET state=?,result_ref=?,error_code=?,revision=revision+1,updated_at=? WHERE id=? AND fence=?",to,reference,error,now,lease.id(),lease.fence());
            event(lease.id(),row.revision()+1,to,lease.fence(),now);return row(lease.id(),false).receipt();
        });
    }
    private Receipt same(Row row,Scope scope,String requestHash) {
        if(row.mountId()!=scope.mount().id()||!Objects.equals(row.workspaceId(),scope.actor().workspaceId())||!row.requestHash().equals(requestHash))throw new ApplicationFault("OPERATION_IDEMPOTENCY_CONFLICT",409,"同一操作标识的请求参数已改变，请检查原操作记录");
        return row.receipt();
    }
    private Scope authorize(Actor supplied,String key) {
        Actor current=accounts.current(supplied);
        if(current.workspaceId()!=null&&!accounts.workspaceEnabled(current.workspaceId()))throw denied();
        MountView mount=drives.listMounts(current).stream().filter(m->m.key().equals(key)&&m.enabled()).findFirst().orElseThrow(StorageOperationJournal::denied);
        if(current.workspaceId()!=null&&!Objects.equals(current.workspaceId(),mount.workspaceId()))throw denied();
        return new Scope(current,mount);
    }
    private List<Row> byKey(long actor,String type,String hash){return jdbc.query("SELECT "+COLUMNS+" FROM sc_storage_operation WHERE actor_id=? AND type=? AND idempotency_hash=?",this::map,actor,type,hash);}
    private Row row(String id,boolean lock) {
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM sc_storage_operation WHERE id=?"+(lock?" FOR UPDATE":""),this::map,id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private Row map(ResultSet r,int n)throws SQLException {return new Row(r.getLong("row_no"),r.getString("id"),r.getLong("actor_id"),r.getObject("workspace_id",Long.class),r.getLong("mount_id"),r.getString("type"),r.getString("request_hash"),r.getString("state"),r.getString("result_ref"),r.getString("error_code"),r.getInt("attempt"),r.getLong("fence"),r.getLong("revision"),r.getLong("created_at"),r.getLong("updated_at"));}
    private void event(String id,long sequence,String state,long fence,long now){jdbc.update("INSERT INTO sc_storage_operation_event(operation_id,sequence_no,state,fence,created_at) VALUES(?,?,?,?,?)",id,sequence,state,fence,now);}
    private static String sha256(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void validId(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw invalid();}catch(IllegalArgumentException bad){throw invalid();}}
    private static ApplicationFault invalid(){return new ApplicationFault("OPERATION_INPUT_INVALID",400,"操作记录参数无效");}
    private static ApplicationFault conflict(){return new ApplicationFault("OPERATION_CONFLICT",409,"操作已开始或状态已变化，请读取原操作结果，不要重复执行");}
    private static ApplicationFault missing(){return new ApplicationFault("OPERATION_NOT_FOUND",404,"操作记录不存在");}
    private static ApplicationFault denied(){return new ApplicationFault("OPERATION_FORBIDDEN",403,"当前没有此云盘的操作记录权限");}
}
