package io.studentcloud.course;

import io.studentcloud.account.*;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Content-local two-party handover. The identity port is rechecked; never query platform tables. */
public final class CourseOwnershipTransferService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final AccountDirectory accounts;
    public CourseOwnershipTransferService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,AccountDirectory accounts,boolean enabled){
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(manager);tx.setTimeout(15);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock=Objects.requireNonNull(clock);this.enabled=enabled;this.accounts=Objects.requireNonNull(accounts);
    }
    public record Transfer(String id,long resourceId,long fromAccountId,long toAccountId,long expectedRevision,
            String state,long expiresAt,long createdAt,Long completedAt){}
    public record Recipient(long id,String accountId,String username){}
    public record Snapshot(long actorId,long resourceId,String resourceName,long ownershipRevision,boolean canCreate,List<Recipient> candidates,List<Transfer> transfers){}
    public Snapshot snapshot(Actor supplied,long courseId){
        gate();return tx.execute(status->{var actor=current(supplied);long workspace=courseId;
            long revision=lock(workspace);
            if(!participant(actor,workspace))throw denied();
            boolean creator=jdbc.queryForObject("SELECT owner_id FROM sc_shared_course WHERE id=?",Long.class,workspace)==actor.id();
            var candidates=new ArrayList<Recipient>();
            if(creator){
                for(long id:jdbc.queryForList("SELECT account_id FROM sc_course_member WHERE course_id=? AND state='ACTIVE' AND account_id<>? ORDER BY account_id LIMIT 100",Long.class,workspace,actor.id())){
                    try{var candidate=accounts.account(id);if(candidate.role()==Role.TENANT_ADMIN&&Objects.equals(candidate.workspaceId(),actor.workspaceId()))candidates.add(new Recipient(candidate.id(),candidate.accountId(),candidate.username()));}
                    catch(ApplicationFault unavailable){if(unavailable.httpStatus()!=401&&unavailable.httpStatus()!=404)throw unavailable;}
                }
            }
            return new Snapshot(actor.id(),workspace,jdbc.queryForObject("SELECT name FROM sc_shared_course WHERE id=?",String.class,workspace),revision,creator,List.copyOf(candidates),query("WHERE resource_id=? AND (from_account_id=? OR to_account_id=?) ORDER BY created_at DESC,id DESC LIMIT 50",workspace,actor.id(),actor.id()).stream().map(this::expire).toList());
        });
    }
    public Transfer create(Actor supplied,long courseId,long recipientId,long expectedRevision,long expiresAt,String key){
        gate();if(key==null||!key.matches("[A-Za-z0-9._:-]{1,100}")||expectedRevision<0)throw invalid();
        return tx.execute(status->{
            var actor=current(supplied);long workspace=courseId;long revision=lock(workspace);
            var repeated=query("WHERE from_account_id=? AND idempotency_key=? FOR UPDATE",actor.id(),key);
            if(!repeated.isEmpty()){
                var old=repeated.getFirst();
                if(old.resourceId()!=workspace||old.toAccountId()!=recipientId||old.expectedRevision()!=expectedRevision||old.expiresAt()!=expiresAt)throw conflict("同一请求标识的参数已改变");
                return expire(old);
            }
            if(expiresAt<=clock.millis()||expiresAt>clock.millis()+7*86400000L)throw invalid();
            var parties=parties(actor.id(),recipientId);var from=parties.get(actor.id());var to=parties.get(recipientId);
            eligible(from,to,workspace);
            if(revision!=expectedRevision)throw conflict("课程管理版本已改变，请刷新");
            jdbc.update("UPDATE sc_course_ownership_transfer SET state='EXPIRED' WHERE resource_id=? AND state='PENDING' AND expires_at<=?",workspace,clock.millis());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_ownership_transfer WHERE resource_id=? AND state='PENDING'",Long.class,workspace)>0)throw conflict("该课程已有待确认交接");
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sc_course_ownership_transfer(id,resource_id,from_account_id,to_account_id,expected_revision,state,expires_at,created_at,idempotency_key) VALUES(?,?,?,?,?,'PENDING',?,?,?)",id,workspace,actor.id(),recipientId,expectedRevision,expiresAt,clock.millis(),key);
            return get(id);
        });
    }
    public Transfer decide(Actor supplied,long courseId,String id,String action){
        gate();if(!List.of("accept","reject","cancel").contains(action))throw invalid();
        return tx.execute(status->{
            var actor=current(supplied);var observed=get(id);
            if(actor.id()!=observed.fromAccountId()&&actor.id()!=observed.toAccountId())throw denied();
            if(observed.resourceId()!=courseId)throw denied();
            long revision=lock(observed.resourceId());var row=getLocked(id);
            boolean sender=action.equals("cancel");
            if(actor.id()!=(sender?row.fromAccountId():row.toAccountId()))throw denied();
            row=expire(row);
            if(row.state().equals("EXPIRED"))return row;
            String desired=switch(action){case "accept"->"COMPLETED";case "reject"->"REJECTED";default->"CANCELLED";};
            if(row.state().equals(desired))return row;
            if(!row.state().equals("PENDING"))throw conflict("交接已结束");
            if(action.equals("accept")){
                jdbc.update("INSERT INTO sc_content_owner_guard(account_id) VALUES(?) ON DUPLICATE KEY UPDATE account_id=account_id",row.toAccountId());
                jdbc.queryForObject("SELECT account_id FROM sc_content_owner_guard WHERE account_id=? FOR UPDATE",Long.class,row.toAccountId());
                var parties=parties(row.fromAccountId(),row.toAccountId());
                eligible(parties.get(row.fromAccountId()),parties.get(row.toAccountId()),row.resourceId());
                if(revision!=row.expectedRevision())throw conflict("课程管理版本已改变，请刷新");
                jdbc.update("UPDATE sc_course_ownership_transfer SET state='ACCEPTED' WHERE id=?",id);
                if(jdbc.update("UPDATE sc_shared_course SET owner_id=?,ownership_revision=ownership_revision+1,updated_at=? WHERE id=? AND owner_id=? AND ownership_revision=?",row.toAccountId(),clock.millis(),row.resourceId(),row.fromAccountId(),row.expectedRevision())!=1)throw conflict("课程管理版本已改变");
                jdbc.update("UPDATE sc_course_member SET state='LEFT',upload_status='NONE',updated_at=? WHERE course_id=? AND account_id=?",clock.millis(),row.resourceId(),row.fromAccountId());
                jdbc.update("UPDATE sc_course_invitation SET revoked_at=? WHERE course_id=? AND revoked_at IS NULL",clock.millis(),row.resourceId());
            }
            jdbc.update("UPDATE sc_course_ownership_transfer SET state=?,completed_at=? WHERE id=?",desired,action.equals("accept")?clock.millis():null,id);
            return get(id);
        });
    }
    private Actor current(Actor supplied){return accounts.current(supplied);}
    private long lock(long id){
        // Same lock order as CourseMaterialService: budget first, then course.
        jdbc.queryForObject("SELECT id FROM sc_course_storage_budget WHERE id=1 FOR UPDATE",Integer.class);
        var rows=jdbc.query("SELECT ownership_revision,enabled FROM sc_shared_course WHERE id=? AND deleted_at IS NULL FOR UPDATE",(r,i)->r.getBoolean("enabled")?r.getLong("ownership_revision"):-1L,id);
        if(rows.size()!=1||rows.getFirst()<0)throw denied();return rows.getFirst();
    }
    private boolean participant(Actor actor,long course){
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_shared_course WHERE id=? AND owner_id=?",Long.class,course,actor.id())==1
            ||jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_member WHERE course_id=? AND account_id=? AND state='ACTIVE'",Long.class,course,actor.id())==1;
    }
    private Map<Long,Actor> parties(long from,long to){
        if(from==to)throw invalid();return Map.of(from,accounts.account(from),to,accounts.account(to));
    }
    private void eligible(Actor from,Actor to,long course){
        // A demoted former workspace admin retains only the right to hand over a course they already own.
        if((from.role()!=Role.TENANT_ADMIN&&from.role()!=Role.USER)||to.role()!=Role.TENANT_ADMIN||from.workspaceId()==null||!Objects.equals(from.workspaceId(),to.workspaceId()))throw denied();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_shared_course WHERE id=? AND owner_id=?",Long.class,course,from.id())!=1
            ||jdbc.queryForObject("SELECT COUNT(*) FROM sc_course_member WHERE course_id=? AND account_id=? AND state='ACTIVE'",Long.class,course,to.id())!=1)throw denied();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_shared_course WHERE owner_id=? AND deleted_at IS NULL",Long.class,to.id())>=50)throw conflict("接任者创建的课程已达上限");
        long existing=jdbc.queryForObject("SELECT COALESCE(SUM(used_bytes),0) FROM sc_shared_course WHERE owner_id=? AND deleted_at IS NULL",Long.class,to.id());
        long incoming=jdbc.queryForObject("SELECT used_bytes FROM sc_shared_course WHERE id=?",Long.class,course);
        if(existing>2L*1024*1024*1024-incoming)throw conflict("接任者课程总容量超过 2 GiB 上限");
    }
    private Transfer expire(Transfer row){
        if(row.state().equals("PENDING")&&row.expiresAt()<=clock.millis()){
            jdbc.update("UPDATE sc_course_ownership_transfer SET state='EXPIRED' WHERE id=? AND state='PENDING'",row.id());return get(row.id());
        }return row;
    }
    private Transfer getLocked(String id){var rows=query("WHERE id=? FOR UPDATE",id);if(rows.size()!=1)throw new ApplicationFault("TRANSFER_NOT_FOUND",404,"交接不存在");return rows.getFirst();}
    private Transfer get(String id){var rows=query("WHERE id=?",id);if(rows.size()!=1)throw new ApplicationFault("TRANSFER_NOT_FOUND",404,"交接不存在");return rows.getFirst();}
    private List<Transfer> query(String suffix,Object... args){return jdbc.query("SELECT * FROM sc_course_ownership_transfer "+suffix,(r,i)->new Transfer(r.getString("id"),r.getLong("resource_id"),r.getLong("from_account_id"),r.getLong("to_account_id"),r.getLong("expected_revision"),r.getString("state"),r.getLong("expires_at"),r.getLong("created_at"),r.getObject("completed_at",Long.class)),args);}
    private void gate(){if(!enabled)throw new ApplicationFault("OWNERSHIP_TRANSFER_DISABLED",404,"管理交接未启用");}
    private static ApplicationFault invalid(){return new ApplicationFault("TRANSFER_INVALID",400,"请核对接任者、版本、请求标识及七天内的有效期");}
    private static ApplicationFault denied(){return new ApplicationFault("TRANSFER_DENIED",403,"仅限双方本人及同空间当前有效资格");}
    private static ApplicationFault conflict(String message){return new ApplicationFault("TRANSFER_CONFLICT",409,message);}
}
