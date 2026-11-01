package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Platform-local two-party handover. Only management roles change: no files, grants or Memory. */
public final class WorkspaceOwnershipTransferService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    public WorkspaceOwnershipTransferService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,boolean enabled){
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(manager);tx.setTimeout(15);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock=Objects.requireNonNull(clock);this.enabled=enabled;
    }
    public record Transfer(String id,long resourceId,long fromAccountId,long toAccountId,long expectedRevision,
            String state,long expiresAt,long createdAt,Long completedAt){}
    public record Recipient(long id,String accountId,String username){}
    public record Snapshot(long actorId,long resourceId,String resourceName,long ownershipRevision,boolean canCreate,List<Recipient> candidates,List<Transfer> transfers){}
    public Snapshot snapshot(Actor supplied){
        gate();return tx.execute(status->{var actor=current(supplied);long workspace=workspace(actor);
            long revision=lock(workspace);
            var candidates=actor.role()==Role.TENANT_ADMIN?jdbc.query("SELECT id,account_id,username FROM sc_account WHERE workspace_id=? AND role='USER' AND enabled=TRUE ORDER BY id LIMIT 100",(r,i)->new Recipient(r.getLong("id"),r.getString("account_id"),r.getString("username")),workspace):List.<Recipient>of();
            return new Snapshot(actor.id(),workspace,jdbc.queryForObject("SELECT name FROM sc_workspace WHERE id=?",String.class,workspace),revision,actor.role()==Role.TENANT_ADMIN,candidates,query("WHERE resource_id=? AND (from_account_id=? OR to_account_id=?) ORDER BY created_at DESC,id DESC LIMIT 50",workspace,actor.id(),actor.id()).stream().map(this::expire).toList());
        });
    }
    public Transfer create(Actor supplied,long recipientId,long expectedRevision,long expiresAt,String key){
        gate();if(key==null||!key.matches("[A-Za-z0-9._:-]{1,100}")||expectedRevision<0)throw invalid();
        return tx.execute(status->{
            var actor=current(supplied);long workspace=workspace(actor);long revision=lock(workspace);
            var repeated=query("WHERE from_account_id=? AND idempotency_key=? FOR UPDATE",actor.id(),key);
            if(!repeated.isEmpty()){
                var old=repeated.getFirst();
                if(old.resourceId()!=workspace||old.toAccountId()!=recipientId||old.expectedRevision()!=expectedRevision||old.expiresAt()!=expiresAt)throw conflict("同一请求标识的参数已改变");
                return expire(old);
            }
            if(expiresAt<=clock.millis()||expiresAt>clock.millis()+7*86400000L)throw invalid();
            var parties=parties(actor.id(),recipientId);var from=parties.get(actor.id());var to=parties.get(recipientId);
            eligible(from,to,workspace);
            if(revision!=expectedRevision)throw conflict("空间管理版本已改变，请刷新");
            jdbc.update("UPDATE sc_workspace_ownership_transfer SET state='EXPIRED' WHERE resource_id=? AND state='PENDING' AND expires_at<=?",workspace,clock.millis());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace_ownership_transfer WHERE resource_id=? AND state='PENDING'",Long.class,workspace)>0)throw conflict("该空间已有待确认交接");
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sc_workspace_ownership_transfer(id,resource_id,from_account_id,to_account_id,expected_revision,state,expires_at,created_at,idempotency_key) VALUES(?,?,?,?,?,'PENDING',?,?,?)",id,workspace,actor.id(),recipientId,expectedRevision,expiresAt,clock.millis(),key);
            return get(id);
        });
    }
    public Transfer decide(Actor supplied,String id,String action){
        gate();if(!List.of("accept","reject","cancel").contains(action))throw invalid();
        return tx.execute(status->{
            var actor=current(supplied);var observed=get(id);
            if(actor.id()!=observed.fromAccountId()&&actor.id()!=observed.toAccountId())throw denied();
            if(!Objects.equals(actor.workspaceId(),observed.resourceId()))throw denied();
            long revision=lock(observed.resourceId());var row=getLocked(id);
            boolean sender=action.equals("cancel");
            if(actor.id()!=(sender?row.fromAccountId():row.toAccountId()))throw denied();
            row=expire(row);
            if(row.state().equals("EXPIRED"))return row;
            String desired=switch(action){case "accept"->"COMPLETED";case "reject"->"REJECTED";default->"CANCELLED";};
            if(row.state().equals(desired))return row;
            if(!row.state().equals("PENDING"))throw conflict("交接已结束");
            if(action.equals("accept")){
                var parties=parties(row.fromAccountId(),row.toAccountId());
                eligible(parties.get(row.fromAccountId()),parties.get(row.toAccountId()),row.resourceId());
                if(revision!=row.expectedRevision())throw conflict("空间管理版本已改变，请刷新");
                jdbc.update("UPDATE sc_workspace_ownership_transfer SET state='ACCEPTED' WHERE id=?",id);
                // Resource lock + row locks make demotion/promotion one atomic operation. Never leave an unmanaged interval.
                if(jdbc.update("UPDATE sc_account SET role='USER',session_version=session_version+1 WHERE id=? AND role='TENANT_ADMIN' AND enabled=TRUE",row.fromAccountId())!=1)throw conflict("前任管理资格已改变");
                if(jdbc.update("UPDATE sc_account SET role='TENANT_ADMIN' WHERE id=? AND role='USER' AND enabled=TRUE",row.toAccountId())!=1)throw conflict("接任资格已改变");
                jdbc.update("DELETE FROM sc_session WHERE account_id=?",row.fromAccountId());
                jdbc.update("UPDATE sc_workspace SET ownership_revision=ownership_revision+1 WHERE id=?",row.resourceId());
            }
            jdbc.update("UPDATE sc_workspace_ownership_transfer SET state=?,completed_at=? WHERE id=?",desired,action.equals("accept")?clock.millis():null,id);
            return get(id);
        });
    }
    private Actor current(Actor supplied){return new JdbcAccountDirectory(jdbc).current(supplied);}
    private long workspace(Actor actor){if(actor.role()==Role.ADMIN||actor.workspaceId()==null)throw denied();return actor.workspaceId();}
    private long lock(long id){
        var rows=jdbc.query("SELECT ownership_revision,enabled FROM sc_workspace WHERE id=? FOR UPDATE",(r,i)->r.getBoolean("enabled")?r.getLong("ownership_revision"):-1L,id);
        if(rows.size()!=1||rows.getFirst()<0)throw denied();return rows.getFirst();
    }
    private Map<Long,Actor> parties(long from,long to){
        if(from==to)throw invalid();
        var result=new HashMap<Long,Actor>();
        for(long id:new TreeSet<>(List.of(from,to))){
            var rows=jdbc.query("SELECT id,account_id,username,email,role,workspace_id,enabled FROM sc_account WHERE id=? FOR UPDATE",(r,i)->{
                if(!r.getBoolean("enabled"))throw denied();
                return new Actor(r.getLong("id"),r.getString("account_id"),r.getString("username"),r.getString("email"),Role.valueOf(r.getString("role")),r.getObject("workspace_id",Long.class));
            },id);
            if(rows.size()!=1)throw denied();result.put(id,rows.getFirst());
        }
        return result;
    }
    private static void eligible(Actor from,Actor to,long workspace){
        if(from.role()!=Role.TENANT_ADMIN||to.role()!=Role.USER||!Objects.equals(from.workspaceId(),workspace)||!Objects.equals(to.workspaceId(),workspace))throw denied();
    }
    private Transfer expire(Transfer row){
        if(row.state().equals("PENDING")&&row.expiresAt()<=clock.millis()){
            jdbc.update("UPDATE sc_workspace_ownership_transfer SET state='EXPIRED' WHERE id=? AND state='PENDING'",row.id());return get(row.id());
        }return row;
    }
    private Transfer getLocked(String id){var rows=query("WHERE id=? FOR UPDATE",id);if(rows.size()!=1)throw new ApplicationFault("TRANSFER_NOT_FOUND",404,"交接不存在");return rows.getFirst();}
    private Transfer get(String id){var rows=query("WHERE id=?",id);if(rows.size()!=1)throw new ApplicationFault("TRANSFER_NOT_FOUND",404,"交接不存在");return rows.getFirst();}
    private List<Transfer> query(String suffix,Object... args){return jdbc.query("SELECT * FROM sc_workspace_ownership_transfer "+suffix,(r,i)->new Transfer(r.getString("id"),r.getLong("resource_id"),r.getLong("from_account_id"),r.getLong("to_account_id"),r.getLong("expected_revision"),r.getString("state"),r.getLong("expires_at"),r.getLong("created_at"),r.getObject("completed_at",Long.class)),args);}
    private void gate(){if(!enabled)throw new ApplicationFault("OWNERSHIP_TRANSFER_DISABLED",404,"管理交接未启用");}
    private static ApplicationFault invalid(){return new ApplicationFault("TRANSFER_INVALID",400,"请核对接任者、版本、请求标识及七天内的有效期");}
    private static ApplicationFault denied(){return new ApplicationFault("TRANSFER_DENIED",403,"仅限双方本人及同空间当前有效资格");}
    private static ApplicationFault conflict(String message){return new ApplicationFault("TRANSFER_CONFLICT",409,message);}
}
