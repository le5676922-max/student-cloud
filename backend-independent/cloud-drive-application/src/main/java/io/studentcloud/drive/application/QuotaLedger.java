package io.studentcloud.drive.application;

import io.studentcloud.common.ApplicationFault;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 内容服务内部容量端口；Scope 必须由已鉴权的挂载授权生成，不能接受浏览器声明的主体。 */
public final class QuotaLedger {
    public record Scope(long mountId, long ownerId, String generation) {
        public Scope {
            if (mountId < 1 || ownerId < 1 || generation == null || generation.isBlank() || generation.length() > 128)
                throw new IllegalArgumentException("Invalid internal quota scope");
        }
    }
    public record View(Long limitBytes, Long usedBytes, long reservedBytes, Long availableBytes,
                       String usageState, Long reconciledAt, long pendingWrites) {}
    public record Reservation(String id, String state, long maxBytes, Long actualBytes) {}
    private record Account(String id, long used, long held, String state, Long reconciledAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public QuotaLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.clock = Objects.requireNonNull(clock);
        tx = new TransactionTemplate(transactions);
        // 意图独立提交：文件写入之后的 SQL 回滚不能使已可能发布的容量凭空消失。
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(30);
    }

    public View view(Scope scope, Long limit) {
        return locked(scope, limit, a -> snapshot(a, limit));
    }

    public View reconcile(Scope scope, Long limit, LongSupplier physicalBytes) {
        return locked(scope, limit, a -> {
            if (a.held != 0 || pending(a.id)>0) throw fault("QUOTA_PENDING", "存在待确认的写入，不能校准或释放其预留容量");
            long bytes = physicalBytes.getAsLong();
            if (bytes < 0) throw fault("QUOTA_INVALID", "无法确定实际占用量");
            long now = clock.millis();
            jdbc.update("UPDATE sc_quota_account SET used_bytes=?,usage_state='EXACT',reconciled_at=?,revision=revision+1 WHERE id=?", bytes, now, a.id);
            entry(a.id, "reconcile-" + id(), "RECONCILE", bytes - a.used);
            return snapshot(new Account(a.id, bytes, 0, "EXACT", now), limit);
        });
    }

    public Reservation reserve(Scope scope, Long limit, String operationId, long maxBytes) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_.:-]{1,128}") || maxBytes < 0)
            throw fault("QUOTA_INVALID", "容量操作参数无效");
        return locked(scope, limit, a -> {
            var existing = jdbc.query("SELECT id,state,max_bytes,actual_bytes FROM sc_quota_reservation WHERE quota_account_id=? AND operation_id=?",
                    (r,n) -> new Reservation(r.getString(1), r.getString(2), r.getLong(3), r.getObject(4,Long.class)), a.id, operationId);
            if (!existing.isEmpty()) {
                var prior = existing.getFirst();
                if (prior.maxBytes != maxBytes) throw fault("QUOTA_CONFLICT", "同一操作的容量参数已变化");
                return prior;
            }
            if (maxBytes>0 && a.state.equals("UNKNOWN")) throw fault("QUOTA_RECONCILE_REQUIRED", "容量尚未校准，暂不能预留");
            long occupied = add(a.used, a.held), requested = add(occupied, maxBytes);
            if (maxBytes>0 && limit != null && requested > limit) throw new ApplicationFault("DRIVE_QUOTA_EXCEEDED",409,"剩余容量不足，包含正在处理的预留容量");
            String id = id();
            jdbc.update("INSERT INTO sc_quota_reservation(id,quota_account_id,operation_id,max_bytes,state,expires_at) VALUES(?,?,?,?,'RESERVED',?)",
                    id, a.id, operationId, maxBytes, clock.millis() + 86400000L);
            jdbc.update("UPDATE sc_quota_account SET reserved_bytes=?,usage_state=CASE WHEN usage_state='UNKNOWN' THEN 'UNKNOWN' ELSE 'ESTIMATED' END,revision=revision+1 WHERE id=?", add(a.held,maxBytes), a.id);
            return new Reservation(id, "RESERVED", maxBytes, null);
        });
    }

    public void settle(Scope scope, String reservationId, long actualBytes) {
        transition(scope, reservationId, actualBytes, false);
    }

    /** 仅限调用方已证明存储没有副作用；未知结果不调用此方法，也不按 expires_at 自动释放。 */
    public void releaseConfirmedFailure(Scope scope, String reservationId) {
        transition(scope, reservationId, 0, true);
    }

    /** 零增长的回收/恢复/清理仍可能改变物理占用；保守标记，不自动减账。 */
    public void markEstimated(Scope scope) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE sc_quota_account SET usage_state='ESTIMATED',revision=revision+1 WHERE mount_id=? AND owner_id=? AND owner_scope=? AND usage_state='EXACT'",
                scope.mountId,scope.ownerId,scope.generation));
    }

    private void transition(Scope scope, String reservationId, long actual, boolean release) {
        if (actual < 0) throw fault("QUOTA_INVALID", "实际容量不能为负数");
        tx.executeWithoutResult(status -> {
            var accounts = jdbc.query("SELECT * FROM sc_quota_account WHERE mount_id=? AND owner_id=? AND owner_scope=? FOR UPDATE",
                    (r,n) -> account(r), scope.mountId, scope.ownerId, scope.generation);
            if (accounts.isEmpty()) throw fault("QUOTA_NOT_FOUND", "容量记录不存在");
            var a = accounts.getFirst();
            var rows = jdbc.query("SELECT id,state,max_bytes,actual_bytes FROM sc_quota_reservation WHERE id=? AND quota_account_id=?",
                    (r,n) -> new Reservation(r.getString(1),r.getString(2),r.getLong(3),r.getObject(4,Long.class)), reservationId,a.id);
            if (rows.isEmpty()) throw fault("QUOTA_NOT_FOUND", "容量记录不存在");
            var held = rows.getFirst(); String target = release ? "RELEASED" : "SETTLED";
            if (held.state.equals(target) && (release || Objects.equals(held.actualBytes,actual))) return;
            if (!held.state.equals("RESERVED") || actual > held.maxBytes) throw fault("QUOTA_CONFLICT", "容量结算状态或参数不一致");
            if (a.held < held.maxBytes) throw fault("QUOTA_CONFLICT", "预留容量需要核查");
            long used = release ? a.used : add(a.used,actual);
            jdbc.update("UPDATE sc_quota_reservation SET state=?,actual_bytes=? WHERE id=?",target,release?null:actual,reservationId);
            jdbc.update("UPDATE sc_quota_account SET used_bytes=?,reserved_bytes=?,usage_state=CASE WHEN usage_state='UNKNOWN' THEN 'UNKNOWN' ELSE 'ESTIMATED' END,revision=revision+1 WHERE id=?",
                    used,a.held-held.maxBytes,a.id);
            entry(a.id,reservationId,release?"RELEASE":"WRITE",release?0:actual);
        });
    }

    private <T> T locked(Scope s, Long limit, Function<Account,T> action) {
        if (limit != null && limit < 0) throw fault("QUOTA_INVALID", "容量上限不能为负数");
        return tx.execute(status -> {
            jdbc.update("INSERT INTO sc_quota_account(id,mount_id,owner_id,owner_scope,limit_bytes) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id(),s.mountId,s.ownerId,s.generation,limit);
            var a = jdbc.queryForObject("SELECT * FROM sc_quota_account WHERE mount_id=? AND owner_id=? AND owner_scope=? FOR UPDATE",(r,n)->account(r),s.mountId,s.ownerId,s.generation);
            jdbc.update("UPDATE sc_quota_account SET limit_bytes=? WHERE id=?",limit,a.id);
            return action.apply(a);
        });
    }
    private static Account account(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Account(r.getString("id"),r.getLong("used_bytes"),r.getLong("reserved_bytes"),r.getString("usage_state"),r.getObject("reconciled_at",Long.class));
    }
    private long pending(String account){return jdbc.queryForObject("SELECT COUNT(*) FROM sc_quota_reservation WHERE quota_account_id=? AND state='RESERVED'",Long.class,account);}
    private View snapshot(Account a, Long limit) {
        boolean unknown = a.state.equals("UNKNOWN");
        Long available = unknown || limit == null ? null : Math.max(0,limit-Math.min(limit,add(a.used,a.held)));
        return new View(limit,unknown?null:a.used,a.held,available,a.state,a.reconciledAt,pending(a.id));
    }
    private void entry(String account,String op,String category,long delta) {
        jdbc.update("INSERT INTO sc_quota_entry(id,operation_id,quota_account_id,category,delta_bytes,created_at) VALUES(?,?,?,?,?,?)",id(),op,account,category,delta,clock.millis());
    }
    private static long add(long a,long b) { try { return Math.addExact(a,b); } catch (ArithmeticException overflow) { throw fault("QUOTA_OVERFLOW","容量超出安全计量范围"); } }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
    private static ApplicationFault fault(String code,String message) { return new ApplicationFault(code,409,message); }
}
