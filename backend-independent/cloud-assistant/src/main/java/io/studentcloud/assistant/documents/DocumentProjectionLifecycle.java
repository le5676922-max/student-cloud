package io.studentcloud.assistant.documents;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Intelligence-owned metadata projection. Accept only authenticated, versioned content events.
 * This is NOT a content authority or a parser: no host paths, content DB reads, or plaintext API.
 * Consumers must verify immutable ContentFacts before fetching/processing source bytes. A task
 * fence controls metadata publication only; it cannot fence external filesystem side effects.
 */
public final class DocumentProjectionLifecycle {
    private static final String CONSUMER="document-projection-v1";
    private static final String COLUMNS="file_id,file_version_id,sequence_no,state,source_hash,generation";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    public DocumentProjectionLifecycle(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(Objects.requireNonNull(tx));this.clock=Objects.requireNonNull(clock);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(10);
    }
    public record Event(int schemaVersion,String eventId,String fileId,String fileVersionId,long sequence,
                        String scope,long ownerId,long aclRevision,String sourceHash,boolean deleted) { }
    public record Projection(String fileId,String fileVersionId,long sequence,String state,String sourceHash,long generation) { }
    public record Lease(String fileId,String fileVersionId,long sequence,String sourceHash,long generation) { }

    /** Older/duplicate events are acknowledged durably but cannot replace a newer tombstone.
     * Contention failures roll back the event and projection together; callers retry that same ID.
     */
    public boolean consume(Event event) {
        validate(event);
        try {
            return Boolean.TRUE.equals(tx.execute(status->{
                jdbc.update("INSERT INTO sc_document_consumed_event(consumer,event_id,file_id,sequence_no,processed_at) VALUES(?,?,?,?,?)",
                        CONSUMER,event.eventId(),event.fileId(),event.sequence(),clock.millis());
                var previous=find(event.fileId(),true);
                if(!previous.isEmpty() && previous.getFirst().sequence()>=event.sequence()) return false;
                String state=event.deleted()?"DELETED":"PENDING";
                if(previous.isEmpty()) jdbc.update("INSERT INTO sc_document_projection(file_id,file_version_id,scope,owner_id,acl_revision,sequence_no,state,source_hash,generation,total_pages,covered_pages,updated_at) VALUES(?,?,?,?,?,?,?,?,0,0,0,?)",
                        event.fileId(),event.fileVersionId(),event.scope(),event.ownerId(),event.aclRevision(),event.sequence(),state,event.sourceHash(),clock.millis());
                else jdbc.update("UPDATE sc_document_projection SET file_version_id=?,scope=?,owner_id=?,acl_revision=?,sequence_no=?,state=?,source_hash=?,parser_version=NULL,generation=generation+1,total_pages=0,covered_pages=0,updated_at=? WHERE file_id=?",
                        event.fileVersionId(),event.scope(),event.ownerId(),event.aclRevision(),event.sequence(),state,event.sourceHash(),clock.millis(),event.fileId());
                return true;
            }));
        } catch(DuplicateKeyException conflict) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sc_document_consumed_event WHERE consumer=? AND event_id=?",Boolean.class,CONSUMER,event.eventId()))) return false;
            // A different event raced to create the first row. Do not acknowledge it accidentally.
            throw new ApplicationFault("DOCUMENT_EVENT_CONFLICT",409,"索引事件并发冲突，请使用相同事件标识重试");
        }
    }
    /** Internal metadata read. Public adapters must freshly authorize the source before calling. */
    public Projection get(String fileId) {
        id(fileId);
        return find(fileId,false).stream().findFirst().orElseThrow(()->fault("DOCUMENT_INDEX_NOT_FOUND","索引状态不存在"));
    }
    /** Called only after the worker has freshly verified the exact immutable source/hash.
     * A new generation invalidates every older processing result, including late OCR completion.
     */
    public Lease begin(String fileId,long sequence,String sourceHash,String parserVersion) {
        id(fileId);hash(sourceHash);
        if(sequence<1 || parserVersion==null || !parserVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) throw invalid();
        return tx.execute(status->{
            var before=get(fileId);
            int changed=jdbc.update("UPDATE sc_document_projection SET state='EXTRACTING',parser_version=?,generation=generation+1,updated_at=? WHERE file_id=? AND sequence_no=? AND source_hash=? AND generation=? AND state<>'DELETED'",
                    parserVersion,clock.millis(),fileId,sequence,sourceHash,before.generation());
            if(changed!=1) throw fault("DOCUMENT_SOURCE_CHANGED","资料版本已变化，请重新核验");
            var current=get(fileId);
            return new Lease(current.fileId(),current.fileVersionId(),current.sequence(),current.sourceHash(),current.generation());
        });
    }
    /** Publication is a metadata CAS, never proof of the source's current authorization. */
    public boolean publish(Lease lease,int totalPages,int coveredPages,boolean partial) {
        if(lease==null || totalPages<1 || coveredPages<0 || coveredPages>totalPages) throw invalid();
        id(lease.fileId());id(lease.fileVersionId());hash(lease.sourceHash());
        return jdbc.update("UPDATE sc_document_projection SET state=?,total_pages=?,covered_pages=?,updated_at=? WHERE file_id=? AND file_version_id=? AND sequence_no=? AND source_hash=? AND generation=? AND state='EXTRACTING'",
                partial || coveredPages<totalPages?"PARTIAL":"READY",totalPages,coveredPages,clock.millis(),lease.fileId(),lease.fileVersionId(),lease.sequence(),lease.sourceHash(),lease.generation())==1;
    }
    private List<Projection> find(String id,boolean lock) {
        return jdbc.query("SELECT "+COLUMNS+" FROM sc_document_projection WHERE file_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Projection(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getLong(6)),id);
    }
    private static void validate(Event e) {
        if(e==null) throw invalid();
        if(e.schemaVersion()!=1) throw new ApplicationFault("DOCUMENT_EVENT_SCHEMA_UNSUPPORTED",422,"索引事件版本不兼容，需隔离核查");
        id(e.fileId());id(e.fileVersionId());hash(e.sourceHash());
        if(e.sequence()<1 || e.ownerId()<1 || e.aclRevision()<0 || !List.of("PRIVATE","COURSE").contains(e.scope())
                || e.eventId()==null || !e.eventId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw invalid();
    }
    static void id(String value) {
        if(value==null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid();
    }
    static void hash(String value) {if(value==null || !value.matches("[a-f0-9]{64}")) throw invalid();}
    private static ApplicationFault invalid() {return new ApplicationFault("DOCUMENT_INPUT_INVALID",400,"资料版本或索引参数无效");}
    private static ApplicationFault fault(String code,String message) {return new ApplicationFault(code,409,message);}
}
