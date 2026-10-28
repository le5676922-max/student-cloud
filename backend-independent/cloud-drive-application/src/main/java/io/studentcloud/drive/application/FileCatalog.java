package io.studentcloud.drive.application;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL metadata snapshots only. The caller freshly authorizes and validates the directory on every request. */
public final class FileCatalog {
    private static final long TTL_MILLIS=10_000;
    private static final int MAX_DIRECTORIES=100,MAX_OWNER_RECORDS=50_000,MAX_DIRECTORY_RECORDS=10_000;
    private static final String WHERE="actor_id=? AND mount_id=? AND scope_hash=? AND parent_hash=?";
    private static final String ORDER=" ORDER BY directory DESC,name ASC,path_hash ASC";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    public record Entry(String id,String name,DrivePath path,boolean directory,long size,Instant modifiedAt) {
        FileRecord file(){return new FileRecord(path,name,directory,size,modifiedAt);}
    }
    public record Page(List<Entry> records,long total,int page,int pageSize) { }
    private record Key(long actor,long mount,String scope,String parent,DrivePath path) {
        Object[] args(){return new Object[]{actor,mount,scope,parent};}
    }
    private record Directory(String generation,long revision,long mountRevision,long parentModified,long refreshedAt,boolean valid) { }
    private record Ticket(Key key,String generation,long directoryRevision,long mountRevision) { }

    public FileCatalog(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock){
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(Objects.requireNonNull(transactions));
        this.tx.setTimeout(20);this.clock=Objects.requireNonNull(clock);
    }

    public List<FileRecord> list(long actorId,long mountId,String scope,DrivePath parent,long parentModified,
                                 boolean refresh,Supplier<List<FileRecord>> scan){
        Key key=key(actorId,mountId,scope,parent);
        Objects.requireNonNull(scan);
        if(parentModified<0)throw invalid();
        // Reserve and read a generation in a short transaction, then release all database locks before disk IO.
        Object prepared=tx.execute(status->{
            lockOwner(actorId);long mountRevision=lockMount(mountId);
            Directory directory=directory(key);
            if(!refresh&&fresh(directory,mountRevision,parentModified))return records(key);
            if(directory==null){
                evictForCapacity(key,0,true);
                String generation=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO sc_file_catalog_directory(actor_id,mount_id,scope_hash,parent_hash,parent_path,generation,revision,mount_revision,parent_modified,refreshed_at,valid) VALUES(?,?,?,?,?,?,0,?,?,0,FALSE)",
                        actorId,mountId,key.scope(),key.parent(),parent.value(),generation,mountRevision,parentModified);
                directory=new Directory(generation,0,mountRevision,parentModified,0,false);
            }
            return new Ticket(key,directory.generation(),directory.revision(),mountRevision);
        });
        if(prepared instanceof List<?> cached){
            @SuppressWarnings("unchecked") List<FileRecord> files=(List<FileRecord>)cached;
            return files;
        }
        Ticket ticket=(Ticket)prepared;
        List<FileRecord> scanned=validate(parent,scan.get());
        return tx.execute(status->{
            lockOwner(actorId);long mountRevision=lockMount(mountId);
            Directory directory=directory(key);
            if(mountRevision!=ticket.mountRevision()||directory==null||!directory.generation().equals(ticket.generation()))throw changed();
            if(directory.revision()!=ticket.directoryRevision()){
                // A newer scan may have won. Return only its committed snapshot; a mutation never revives old results.
                if(fresh(directory,mountRevision,parentModified))return records(key);
                throw changed();
            }
            var identities=new HashMap<String,String>();
            jdbc.query("SELECT id,path_hash FROM sc_file_catalog_entry WHERE "+WHERE,
                    (org.springframework.jdbc.core.RowCallbackHandler)row->identities.put(row.getString("path_hash"),row.getString("id")),key.args());
            evictForCapacity(key,scanned.size(),false);
            jdbc.update("DELETE FROM sc_file_catalog_entry WHERE "+WHERE,key.args());
            jdbc.batchUpdate("INSERT INTO sc_file_catalog_entry(id,actor_id,mount_id,scope_hash,parent_hash,path_hash,entry_path,name,directory,byte_size,modified_at,modified_submillis) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    scanned,200,(statement,file)->{
                        String pathHash=hash(file.path().value());
                        statement.setString(1,identities.getOrDefault(pathHash,UUID.randomUUID().toString()));
                        statement.setLong(2,actorId);statement.setLong(3,mountId);
                        statement.setString(4,key.scope());statement.setString(5,key.parent());statement.setString(6,pathHash);
                        statement.setString(7,file.path().value());statement.setString(8,file.name());statement.setBoolean(9,file.directory());
                        statement.setLong(10,file.size());statement.setLong(11,file.modifiedAt().toEpochMilli());
                        statement.setInt(12,file.modifiedAt().getNano()%1_000_000);
                    });
            int updated=jdbc.update("UPDATE sc_file_catalog_directory SET revision=revision+1,mount_revision=?,parent_modified=?,refreshed_at=?,valid=TRUE WHERE "+WHERE+" AND generation=? AND revision=?",
                    mountRevision,parentModified,clock.millis(),actorId,mountId,key.scope(),key.parent(),ticket.generation(),ticket.directoryRevision());
            if(updated!=1)throw changed();
            return records(key);
        });
    }

    /** Paging and name filtering run in SQL against the current, authorization-scoped directory snapshot. */
    public Page page(long actorId,long mountId,String scope,DrivePath parent,int page,int pageSize,String query){
        Key key=key(actorId,mountId,scope,parent);
        if(page<1||page>1_000_000||pageSize<1||pageSize>200||query==null||query.length()>255)throw invalid();
        String filter=query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
        return tx.execute(status->{
            lockOwner(actorId);long revision=lockMount(mountId);Directory directory=directory(key);
            if(directory==null||!directory.valid()||directory.mountRevision()!=revision)throw changed();
            Object[] args=new Object[]{actorId,mountId,key.scope(),key.parent(),"%"+filter+"%"};
            String predicate=WHERE+" AND LOWER(name) LIKE ? ESCAPE '!'";
            long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_entry WHERE "+predicate,Long.class,args));
            long offset=(long)(page-1)*pageSize;
            List<Entry> values=jdbc.query("SELECT * FROM sc_file_catalog_entry WHERE "+predicate+ORDER+" LIMIT ? OFFSET ?",this::entry,
                    actorId,mountId,key.scope(),key.parent(),"%"+filter+"%",pageSize,offset);
            return new Page(List.copyOf(values),total,page,pageSize);
        });
    }

    public void invalidateMount(long mountId){
        if(mountId<1)throw invalid();
        tx.execute(status->{lockMount(mountId);
            jdbc.update("UPDATE sc_file_catalog_mount SET revision=revision+1 WHERE mount_id=?",mountId);
            jdbc.update("UPDATE sc_file_catalog_directory SET valid=FALSE,revision=revision+1 WHERE mount_id=?",mountId);
            return null;
        });
    }

    private void lockOwner(long actor){
        jdbc.update("INSERT INTO sc_file_catalog_owner(actor_id) VALUES(?) ON DUPLICATE KEY UPDATE actor_id=actor_id",actor);
        jdbc.queryForObject("SELECT actor_id FROM sc_file_catalog_owner WHERE actor_id=? FOR UPDATE",Long.class,actor);
    }
    private long lockMount(long mount){
        jdbc.update("INSERT INTO sc_file_catalog_mount(mount_id,revision) VALUES(?,0) ON DUPLICATE KEY UPDATE mount_id=mount_id",mount);
        return Objects.requireNonNull(jdbc.queryForObject("SELECT revision FROM sc_file_catalog_mount WHERE mount_id=? FOR UPDATE",Long.class,mount));
    }
    private Directory directory(Key key){
        var rows=jdbc.query("SELECT generation,revision,mount_revision,parent_modified,refreshed_at,valid FROM sc_file_catalog_directory WHERE "+WHERE,
                (r,i)->new Directory(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getBoolean(6)),key.args());
        return rows.isEmpty()?null:rows.getFirst();
    }
    private boolean fresh(Directory directory,long mountRevision,long parentModified){
        long now=clock.millis();return directory!=null&&directory.valid()&&directory.mountRevision()==mountRevision&&directory.parentModified()==parentModified
                &&now>=directory.refreshedAt()&&now-directory.refreshedAt()<TTL_MILLIS;
    }
    private List<FileRecord> records(Key key){return jdbc.query("SELECT * FROM sc_file_catalog_entry WHERE "+WHERE+ORDER,this::entry,key.args()).stream().map(Entry::file).toList();}
    private Entry entry(ResultSet row,int index)throws SQLException{return new Entry(row.getString("id"),row.getString("name"),DrivePath.of(row.getString("entry_path")),row.getBoolean("directory"),row.getLong("byte_size"),Instant.ofEpochMilli(row.getLong("modified_at")).plusNanos(row.getInt("modified_submillis")));}

    /** Called under the owner lock. Eviction is bounded by the owner's directory budget, never by file count. */
    private void evictForCapacity(Key keep,int incoming,boolean newDirectory){
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_directory WHERE actor_id=?",Long.class,keep.actor()));
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_entry WHERE actor_id=?",Long.class,keep.actor()));
        long replaced=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_file_catalog_entry WHERE "+WHERE,Long.class,keep.args()));
        long projected=total-replaced+incoming;
        if(count+(newDirectory?1:0)<=MAX_DIRECTORIES&&projected<=MAX_OWNER_RECORDS)return;
        var oldest=jdbc.query("SELECT mount_id,scope_hash,parent_hash,parent_path FROM sc_file_catalog_directory WHERE actor_id=? ORDER BY refreshed_at,mount_id,scope_hash,parent_hash LIMIT 100",
                (r,i)->new Key(keep.actor(),r.getLong(1),r.getString(2),r.getString(3),DrivePath.of(r.getString(4))),keep.actor());
        for(Key victim:oldest){
            if(victim.mount()==keep.mount()&&victim.scope().equals(keep.scope())&&victim.parent().equals(keep.parent()))continue;
            int removed=jdbc.update("DELETE FROM sc_file_catalog_entry WHERE "+WHERE,victim.args());
            jdbc.update("DELETE FROM sc_file_catalog_directory WHERE "+WHERE,victim.args());
            count--;projected-=removed;
            if(count+(newDirectory?1:0)<=MAX_DIRECTORIES&&projected<=MAX_OWNER_RECORDS)return;
        }
        throw new ApplicationFault("CATALOG_BUDGET",409,"目录索引超过当前预算，请缩小目录范围");
    }
    private static List<FileRecord> validate(DrivePath parent,List<FileRecord> files){
        if(files==null||files.size()>MAX_DIRECTORY_RECORDS)throw new ApplicationFault("CATALOG_BUDGET",409,"单个目录最多支持10000项，请先整理目录");
        var paths=new HashSet<DrivePath>();
        for(FileRecord file:files){
            if(file==null||file.path()==null||file.path().isRoot()||!file.path().parent().equals(parent)||file.name()==null||file.name().length()>255
                    ||!parent.child(file.name()).equals(file.path())||file.size()<0||file.modifiedAt()==null||!paths.add(file.path()))throw invalid();
        }
        return List.copyOf(files);
    }
    private static Key key(long actor,long mount,String scope,DrivePath parent){
        if(actor<1||mount<1||scope==null||scope.isBlank()||scope.length()>4096||parent==null)throw invalid();
        return new Key(actor,mount,hash(scope),hash(parent.value()),parent);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ApplicationFault invalid(){return new ApplicationFault("CATALOG_INVALID",400,"目录索引或分页参数无效");}
    private static ApplicationFault changed(){return new ApplicationFault("CATALOG_CHANGED",409,"目录在读取期间发生变化，请刷新后重试");}
}
