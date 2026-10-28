package io.studentcloud.content.application;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Confirmed, owner-bound commands; no network/IO under the durable claim transaction. */
public final class AgentCommandService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;
    private final AccountDirectory accounts;private final DriveOperations mounts;private final ObjectMapper json;
    private final io.studentcloud.content.learning.LearningLibraryService learning;
    public AgentCommandService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,DriveOperations mounts,ObjectMapper json){
        this(jdbc,manager,clock,accounts,mounts,json,null);
    }
    public AgentCommandService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,DriveOperations mounts,ObjectMapper json,io.studentcloud.content.learning.LearningLibraryService learning){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.clock=clock;this.accounts=accounts;this.mounts=mounts;this.json=json;
        this.learning=learning;
    }
    public record Step(String op,String sourcePath,String targetPath,Long courseId,String tags){
        public Step(String op,String sourcePath,String targetPath){this(op,sourcePath,targetPath,null,null);}
    }
    public record Preview(String previewToken,long expiresAt,List<Step> steps){}
    public record StepResult(int index,String op,boolean success,String message){}
    public record Execution(String status,List<StepResult> results,boolean replayed){}
    public record Snapshot(String path,boolean exists,boolean directory,long size,long modifiedAt){}
    public record DuplicateGroup(long size,List<String> paths){}
    public record DuplicateReport(int checkedFiles,int skippedFiles,List<DuplicateGroup> groups,int attemptedFiles){}
    private final java.util.concurrent.Semaphore duplicateSlots=new java.util.concurrent.Semaphore(2);
    private record Command(String id,long actorId,Long workspaceId,String key,String steps,String snapshots,String status,String idempotency,String result,long expiresAt,Long startedAt){}

    /** Read-only and bounded: at most 50 files, 2 MiB each and 20 MiB per request, never recursive. */
    public DuplicateReport duplicates(long actorId,Long workspaceId,String key,String directory){
        Actor actor=actor(actorId,workspaceId);validKey(key);
        if(!duplicateSlots.tryAcquire())throw new ApplicationFault("AGENT_BUDGET",429,"重复检查繁忙，请稍后再试");
        try{
            var entries=mounts.list(actor,key,path(directory));
            var groups=new LinkedHashMap<String,List<String>>();var sizes=new HashMap<String,Long>();
            int checked=0,skipped=0,attempted=0;long bytesRead=0;
            for(var file:entries){
                if(file.directory())continue;
                if(attempted>=50 || file.size()>2*1024*1024 || bytesRead+file.size()>20L*1024*1024){skipped++;continue;}
                attempted++;
                try(var handle=mounts.readWithMetadata(actor(actorId,workspaceId),key,file.path())){
                    if(handle.metadata().directory() || handle.metadata().size()!=file.size() || !handle.metadata().modifiedAt().equals(file.modifiedAt())){skipped++;continue;}
                    byte[] bytes=handle.input().readNBytes(2*1024*1024+1);bytesRead+=bytes.length;
                    if(bytes.length!=file.size() || bytes.length>2*1024*1024){skipped++;continue;}
                    try(var fresh=mounts.readWithMetadata(actor(actorId,workspaceId),key,file.path())){
                        if(fresh.metadata().size()!=bytes.length || !fresh.metadata().modifiedAt().equals(file.modifiedAt())){skipped++;continue;}
                    }
                    String digest=bytes.length+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    groups.computeIfAbsent(digest,ignored->new ArrayList<>()).add(file.path().value());sizes.put(digest,(long)bytes.length);checked++;
                }catch(ApplicationFault|java.io.IOException changed){skipped++;}
                catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            }
            return new DuplicateReport(checked,skipped,groups.entrySet().stream().filter(e->e.getValue().size()>1).map(e->new DuplicateGroup(sizes.get(e.getKey()),List.copyOf(e.getValue()))).toList(),attempted);
        }finally{duplicateSlots.release();}
    }

    public Preview preview(long actorId,Long workspaceId,String key,List<Step> input){
        Actor actor=actor(actorId,workspaceId);validKey(key);
        if(input==null || input.isEmpty() || input.size()>12)throw invalid("每个计划须包含1至12个步骤");
        var steps=new ArrayList<Step>();var snapshots=new LinkedHashMap<String,Snapshot>();var createdDirs=new HashSet<String>();var targets=new HashSet<String>();
        for(Step raw:input){
            if(raw!=null && "INDEX_MATERIAL".equals(raw.op())){
                if(learning==null || raw.courseId()==null || raw.courseId()<1 || raw.tags()!=null && raw.tags().length()>200)throw invalid("请选择本人的课程，标签最多200字符");
                DrivePath source=path(raw.sourcePath());
                if(source.isRoot() || !source.name().toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|pdf|docx)$"))throw invalid("只能归档TXT、MD、PDF和DOCX资料");
                var indexed=learning.listMaterials(actor,raw.courseId());
                if(indexed.stream().anyMatch(m->m.storageKey().equals(key)&&m.path().equals(source.value())))throw conflict("文件已归档，请刷新资料索引");
                require(mounts.directoryCapabilities(actor,key,source.parent()),raw.op());
                Snapshot origin=snapshot(actor,key,source);
                if(!origin.exists() || origin.directory() || origin.size()>io.studentcloud.content.learning.LearningLibraryService.MAX_DOCUMENT_BYTES)throw invalid("资料不存在或超过10MiB");
                String identity="index:"+raw.courseId()+":"+source.value();
                if(!targets.add(identity))throw invalid("不能重复归档同一资料");
                snapshots.putIfAbsent(source.value(),origin);
                steps.add(new Step(raw.op(),source.value(),null,raw.courseId(),raw.tags()));
                continue;
            }
            if(raw==null || !Set.of("MKDIR","RENAME","COPY","MOVE").contains(raw.op()))throw invalid("操作类型不支持");
            DrivePath target=path(raw.targetPath());if(target.isRoot() || !targets.add(target.value()))throw invalid("目标路径不能重复或为根目录");
            DrivePath source="MKDIR".equals(raw.op())?null:path(raw.sourcePath());
            if(source!=null && (source.isRoot() || target.equals(source) || target.value().startsWith(source.value()+"/")))throw invalid("来源和目标路径无效");
            if("RENAME".equals(raw.op()) && !source.parent().equals(target.parent()))throw invalid("重命名须在原目录完成");
            Set<String> capabilities=capabilities(actor,key,target.parent(),createdDirs);
            require(capabilities,raw.op());
            Snapshot targetState=snapshot(actor,key,target);if(targetState.exists())throw conflict("目标已存在，不会覆盖");
            snapshots.putIfAbsent(target.value(),targetState);
            if(!createdDirs.contains(target.parent().value()))putSnapshot(snapshots,actor,key,target.parent());
            if(source!=null){
                if(targets.contains(source.value()))throw invalid("同一计划暂不支持使用前面步骤的输出文件");
                Snapshot origin=snapshot(actor,key,source);if(!origin.exists())throw conflict("来源不存在，请刷新目录");
                require(mounts.directoryCapabilities(actor,key,source.parent()),raw.op());
                snapshots.putIfAbsent(source.value(),origin);
            }else createdDirs.add(target.value());
            steps.add(new Step(raw.op(),source==null?null:source.value(),target.value()));
        }
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");long now=clock.millis();
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM sc_agent_command_guard WHERE id=1 FOR UPDATE",Integer.class);
            jdbc.update("DELETE FROM sc_agent_command WHERE actor_id=? AND status='PREVIEW' AND expires_at<?",actor.id(),now);
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_command WHERE actor_id=?",Long.class,actor.id());
            if(count==null || count>=200)throw new ApplicationFault("AGENT_BUDGET",429,"操作记录已达本账号上限，请整理历史记录");
            jdbc.update("INSERT INTO sc_agent_command(id,preview_hash,actor_id,workspace_id,mount_key,command_json,snapshot_json,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,'PREVIEW',?,?)",
                UUID.randomUUID().toString().replace("-",""),hash(token),actor.id(),workspaceId,key,encode(steps),encode(snapshots.values()),now,now+600_000);
        });
        return new Preview(token,now+600_000,List.copyOf(steps));
    }

    public Preview previewBulk(long actorId,Long workspaceId,String key,String type,String directory){
        return previewBulk(actorId,workspaceId,key,type,directory,null,null);
    }
    public Preview previewBulk(long actorId,Long workspaceId,String key,String type,String directory,Long courseId,String tags){
        Actor actor=actor(actorId,workspaceId);DrivePath parent=path(directory);var entries=mounts.list(actor,key,parent);
        if("IMPORT_LEARNING".equals(type)){
            if(learning==null || courseId==null)throw invalid("请选择本人的课程");
            var indexed=learning.listMaterials(actor,courseId);var imports=new ArrayList<Step>();
            for(var file:entries){
                if(file.directory() || file.size()>io.studentcloud.content.learning.LearningLibraryService.MAX_DOCUMENT_BYTES || !file.name().toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|pdf|docx)$"))continue;
                if(indexed.stream().anyMatch(m->m.storageKey().equals(key)&&m.path().equals(file.path().value())))continue;
                imports.add(new Step("INDEX_MATERIAL",file.path().value(),null,courseId,tags));
                if(imports.size()>12)throw invalid("每次最多归档12份资料，请分目录处理");
            }
            if(imports.isEmpty())throw invalid("没有待归档的受支持资料，已归档文件不会重复添加");
            return preview(actorId,workspaceId,key,imports);
        }
        if(!Set.of("ORGANIZE_FILES","RENAME_BY_DATE").contains(type))throw invalid("请选择文件分类或按日期命名");
        var steps=new ArrayList<Step>();var folders=new HashSet<String>();int files=0;
        for(var file:entries){
            if(file.directory())continue;
            if(++files>6)throw invalid("当前目录文件较多，请选择不超过6个文件的目录分批操作");
            String name=file.name();
            if("RENAME_BY_DATE".equals(type)){
                String prefix=DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai")).format(file.modifiedAt())+"_";
                if(name.startsWith(prefix))continue;
                steps.add(new Step("RENAME",file.path().value(),parent.child(prefix+name).value()));
            }else{
                String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
                String category=Set.of("pdf","doc","docx","txt","md","ppt","pptx","xls","xlsx").contains(extension)?"文档":
                    Set.of("png","jpg","jpeg","gif","webp","svg").contains(extension)?"图片":Set.of("mp4","mp3","wav","mkv").contains(extension)?"影音":"其他";
                DrivePath folder=parent.child(category);
                if(folders.add(category) && mounts.stat(actor,key,folder).isEmpty())steps.add(new Step("MKDIR",null,folder.value()));
                steps.add(new Step("MOVE",file.path().value(),folder.child(name).value()));
            }
        }
        if(steps.isEmpty())throw invalid("当前目录没有需要调整的文件");
        return preview(actorId,workspaceId,key,steps);
    }

    public Execution execute(long actorId,Long workspaceId,String token,String idempotencyKey){
        Actor actor=actor(actorId,workspaceId);
        if(token==null || !token.matches("[a-f0-9]{64}") || idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9_-][A-Za-z0-9_-]{0,119}"))throw invalid("确认凭据无效");
        Command command=load(hash(token),actor.id());
        if(!Objects.equals(command.workspaceId(),workspaceId))throw forbidden();
        if(!"PREVIEW".equals(command.status()))return prior(command,idempotencyKey);
        if(command.expiresAt()<=clock.millis())throw conflict("预览已过期，请重新生成");
        List<Step> steps=List.of(json.readValue(command.steps(),Step[].class));
        var createdDirs=new HashSet<String>();
        for(var step:steps){
            if("INDEX_MATERIAL".equals(step.op()))require(mounts.directoryCapabilities(actor,command.key(),path(step.sourcePath()).parent()),step.op());
            else{require(capabilities(actor,command.key(),path(step.targetPath()).parent(),createdDirs),step.op());if("MKDIR".equals(step.op()))createdDirs.add(step.targetPath());}
        }
        for(var snapshot:json.readValue(command.snapshots(),Snapshot[].class))
            if(!snapshot.equals(snapshot(actor,command.key(),path(snapshot.path()))))throw conflict("文件或目录已变化，请重新预览");
        try{
            Boolean claimed=tx.execute(status->{
                jdbc.queryForObject("SELECT id FROM sc_agent_command_guard WHERE id=1 FOR UPDATE",Integer.class);
                return jdbc.update("UPDATE sc_agent_command SET status='RUNNING',idempotency_key=?,started_at=? WHERE id=? AND status='PREVIEW' AND expires_at>?",
                    idempotencyKey,clock.millis(),command.id(),clock.millis())==1;
            });
            if(!Boolean.TRUE.equals(claimed))return prior(load(hash(token),actor.id()),idempotencyKey);
        }catch(DuplicateKeyException conflict){throw conflict("该确认编号已用于其它任务");}
        var results=new ArrayList<StepResult>();
        for(int index=0;index<steps.size();index++){
            Step step=steps.get(index);
            try{
                Actor current=actor(actorId,workspaceId);DrivePath target="INDEX_MATERIAL".equals(step.op())?null:path(step.targetPath());
                if(target!=null && mounts.stat(current,command.key(),target).isPresent())throw conflict("目标已存在，不会覆盖");
                switch(step.op()){
                    case "INDEX_MATERIAL"->learning.addMaterial(current,step.courseId(),command.key(),step.sourcePath(),step.tags());
                    case "MKDIR"->mounts.mkdir(current,command.key(),target);
                    case "RENAME"->mounts.rename(current,command.key(),path(step.sourcePath()),target.name());
                    case "COPY"->mounts.copy(current,command.key(),path(step.sourcePath()),target);
                    case "MOVE"->mounts.move(current,command.key(),path(step.sourcePath()),target);
                    default->throw invalid("操作类型不支持");
                }
                results.add(new StepResult(index,step.op(),true,"已完成"));
            }catch(ApplicationFault failure){
                results.add(new StepResult(index,step.op(),false,failure.getMessage()));
                return finish(command,results,index==0?"FAILED":"PARTIAL");
            }catch(RuntimeException failure){
                results.add(new StepResult(index,step.op(),false,"结果尚未核实，请检查目录，不会自动重放"));
                return finish(command,results,"INDETERMINATE");
            }
            jdbc.update("UPDATE sc_agent_command SET result_json=? WHERE id=? AND status='RUNNING'",encode(new Execution("RUNNING",List.copyOf(results),false)),command.id());
        }
        return finish(command,results,"COMPLETED");
    }
    private Execution finish(Command command,List<StepResult> results,String status){
        var result=new Execution(status,List.copyOf(results),false);
        jdbc.update("UPDATE sc_agent_command SET status=?,result_json=?,finished_at=? WHERE id=? AND status='RUNNING'",status,encode(result),clock.millis(),command.id());
        return result;
    }
    private Execution prior(Command command,String key){
        if(!Objects.equals(command.idempotency(),key))throw conflict("该预览已确认，请查看原任务");
        if("RUNNING".equals(command.status()))return new Execution(command.startedAt()!=null && clock.millis()-command.startedAt()>120_000?"INDETERMINATE":"RUNNING",List.of(),true);
        if(command.result()==null)throw conflict("执行状态需要核查");
        Execution result=json.readValue(command.result(),Execution.class);return new Execution(result.status(),result.results(),true);
    }
    private Command load(String hash,long actor){return jdbc.query("SELECT * FROM sc_agent_command WHERE preview_hash=? AND actor_id=?",
        (r,n)->new Command(r.getString("id"),r.getLong("actor_id"),r.getObject("workspace_id",Long.class),r.getString("mount_key"),r.getString("command_json"),r.getString("snapshot_json"),r.getString("status"),r.getString("idempotency_key"),r.getString("result_json"),r.getLong("expires_at"),r.getObject("started_at",Long.class)),hash,actor).stream().findFirst().orElseThrow(AgentCommandService::forbidden);}
    private Actor actor(long id,Long space){Actor actor=accounts.account(id);if(!Objects.equals(actor.workspaceId(),space))throw forbidden();return actor;}
    private Snapshot snapshot(Actor actor,String key,DrivePath path){return mounts.stat(actor,key,path).map(file->new Snapshot(path.value(),true,file.directory(),file.size(),file.modifiedAt().toEpochMilli())).orElse(new Snapshot(path.value(),false,false,0,0));}
    private void putSnapshot(Map<String,Snapshot> values,Actor actor,String key,DrivePath path){values.putIfAbsent(path.value(),snapshot(actor,key,path));}
    private Set<String> capabilities(Actor actor,String key,DrivePath parent,Set<String> planned){while(planned.contains(parent.value()))parent=parent.parent();return mounts.directoryCapabilities(actor,key,parent);}
    private static void require(Set<String> values,String op){Set<String> needed=switch(op){case "MKDIR"->Set.of("mkdir");case "INDEX_MATERIAL"->Set.of("read");case "COPY"->Set.of("copy","read");case "MOVE"->Set.of("move");case "RENAME"->Set.of("rename");default->Set.of("unsupported");};if(!values.containsAll(needed))throw new ApplicationFault("40300",403,"当前目录没有执行此操作的权限");}
    private String encode(Object value){return json.writeValueAsString(value);}
    private static DrivePath path(String path){try{if(path==null || !path.startsWith("/"))throw invalid("请使用云盘内绝对虚拟路径");return DrivePath.of(path);}catch(IllegalArgumentException invalid){throw invalid("文件路径无效");}}
    private static void validKey(String key){if(key==null || !key.matches("[a-z0-9][a-z0-9_-]{2,63}"))throw invalid("云盘标识无效");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ApplicationFault invalid(String msg){return new ApplicationFault("41000",400,msg);}
    private static ApplicationFault conflict(String msg){return new ApplicationFault("AGENT_CHANGED",409,msg);}
    private static ApplicationFault forbidden(){return new ApplicationFault("40400",404,"操作计划不存在或无权访问");}
}
