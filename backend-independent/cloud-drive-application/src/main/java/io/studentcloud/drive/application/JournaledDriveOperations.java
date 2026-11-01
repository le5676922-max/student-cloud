package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

/**
 * Feature-gated receipt adapter for synchronous LOCAL publications only. Remote adapters can return
 * accepted/queued states, so their results are deliberately delegated without claiming success.
 * DriveOperations has no request-id parameter: these integration receipts are per invocation, NOT
 * HTTP retry deduplication. Clients needing deduplication must use the explicit journal contract
 * with a server-canonical content digest and a stable request key, before consuming upload streams.
 */
public final class JournaledDriveOperations implements DriveOperations {
    private final DriveOperations raw;
    private final StorageOperationJournal journal;
    private final boolean enabled;
    public JournaledDriveOperations(DriveOperations raw,StorageOperationJournal journal,boolean enabled) {
        this.raw=Objects.requireNonNull(raw);this.journal=Objects.requireNonNull(journal);this.enabled=enabled;
    }
    private <T> T write(Actor actor,String key,String type,String request,Supplier<T> action) {
        if(!enabled||raw.listMounts(actor).stream().noneMatch(m->m.key().equals(key)&&m.enabled()&&m.engine().equals("LOCAL")))return action.get();
        // Unique key per invocation: never pretend path+size is an upload content digest.
        boolean[] invoked={false};
        try {
            return journal.perform(actor,key,type,UUID.randomUUID().toString(),request,()->{invoked[0]=true;return action.get();}).value();
        } catch(RuntimeException failure) {
            // Once the adapter was entered, even a later SQL failure may follow a published file.
            // Do not let a lower layer's generic "retry" message invite a second invocation.
            if(invoked[0])throw new ApplicationFault("OPERATION_INDETERMINATE",409,"操作结果待核查，请查看本人的操作记录，不要重复提交");
            throw failure;
        }
    }
    /** Length-prefix fields to prevent ambiguity before the journal hashes this transient string. */
    private static String parameters(String...fields) {
        var value=new StringBuilder();for(String field:fields){if(field==null)value.append("-1:");else value.append(field.length()).append(':').append(field);}return value.toString();
    }
    @Override public FileRecord mkdir(Actor a,String k,DrivePath p){return write(a,k,"MKDIR",parameters(p.value()),()->raw.mkdir(a,k,p));}
    @Override public FileRecord upload(Actor a,String k,DrivePath p,InputStream in,long size){return write(a,k,"UPLOAD",parameters(p.value(),Long.toString(size)),()->raw.upload(a,k,p,in,size));}
    @Override public FileRecord copy(Actor a,String k,DrivePath s,DrivePath t){return write(a,k,"COPY",parameters(s.value(),t.value()),()->raw.copy(a,k,s,t));}
    @Override public FileRecord move(Actor a,String k,DrivePath s,DrivePath t){return write(a,k,"MOVE",parameters(s.value(),t.value()),()->raw.move(a,k,s,t));}
    @Override public FileRecord rename(Actor a,String k,DrivePath p,String name){return write(a,k,"RENAME",parameters(p.value(),name),()->raw.rename(a,k,p,name));}
    @Override public List<MountView> listMounts(Actor a){return raw.listMounts(a);}
    @Override public List<FileRecord> list(Actor a,String k,DrivePath p,boolean refresh){return raw.list(a,k,p,refresh);}
    @Override public Optional<FileRecord> stat(Actor a,String k,DrivePath p){return raw.stat(a,k,p);}
    @Override public Set<String> directoryCapabilities(Actor a,String k,DrivePath p){return raw.directoryCapabilities(a,k,p);}
    @Override public void prepareUpload(Actor a,String k,DrivePath p,long size){raw.prepareUpload(a,k,p,size);}
    @Override public String uploadAuthorization(Actor a,String k,DrivePath p,long size){return raw.uploadAuthorization(a,k,p,size);}
    @Override public ReadHandle readWithMetadata(Actor a,String k,DrivePath p){return raw.readWithMetadata(a,k,p);}
    @Override public void delete(Actor a,String k,DrivePath p){raw.delete(a,k,p);}
    @Override public FileCatalog.Page catalogPage(Actor a,String k,DrivePath p,int page,int size,String query,boolean refresh){return raw.catalogPage(a,k,p,page,size,query,refresh);}
    @Override public List<MountService.TrashView> recycleBin(Actor a,String k){return raw.recycleBin(a,k);}
    @Override public void restoreRecycled(Actor a,String k,String id){raw.restoreRecycled(a,k,id);}
    @Override public void purgeRecycled(Actor a,String k,String id){raw.purgeRecycled(a,k,id);}
}
