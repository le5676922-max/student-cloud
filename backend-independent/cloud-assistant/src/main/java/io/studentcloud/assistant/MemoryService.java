package io.studentcloud.assistant;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit personal memories only; no chat-history collection or external model calls. */
public final class MemoryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AccountDirectory accounts;

    public MemoryService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public List<MemoryView> list(Actor actor) {
        long id = AssistantAccess.current(accounts, actor).id();
        return rows(id);
    }

    public MemorySettings settings(Actor actor) {
        long id = AssistantAccess.current(accounts, actor).id();
        return settings(id);
    }

    public MemoryView create(Actor actor, String content) {
        String value = AssistantAccess.text(content, 500, false);
        return transactions.execute(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            AssistantAccess.current(accounts, current);
            if (!settings(current.id()).enabled()) throw AssistantAccess.fault("41000", 409, "请先启用长期记忆");
            if (rows(current.id()).stream().anyMatch(memory -> memory.content().equals(value))) {
                throw AssistantAccess.fault("41000", 400, "已有相同内容的记忆，请修改内容后再保存");
            }
            if (count(current.id()) >= 50) throw AssistantAccess.fault("41000", 409, "最多保存 50 条个人记忆，请先整理旧内容");
            long now = clock.millis();
            long id = AssistantAccess.insertId(jdbc, "INSERT INTO sc_assistant_memory(account_id,content,version,created_at,updated_at) VALUES(?,?,1,?,?)", current.id(), value, now, now);
            return new MemoryView(id, value, 1, now, now);
        });
    }

    public MemoryView update(Actor actor, long id, long expectedVersion, String content) {
        String value = AssistantAccess.text(content, 500, false);
        version(expectedVersion);
        return transactions.execute(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            AssistantAccess.current(accounts, current);
            MemoryView existing = memory(current.id(), id);
            if (!settings(current.id()).enabled()) throw AssistantAccess.fault("41000", 409, "请先启用长期记忆");
            if (rows(current.id()).stream().anyMatch(memory -> memory.id() != id && memory.content().equals(value))) {
                throw AssistantAccess.fault("41000", 400, "已有相同内容的记忆，请修改内容后再保存");
            }
            if (existing.version() != expectedVersion) throw stale();
            long now = clock.millis();
            if (jdbc.update("UPDATE sc_assistant_memory SET content=?,version=version+1,updated_at=? WHERE id=? AND account_id=? AND version=?", value, now, id, current.id(), expectedVersion) != 1) throw stale();
            return new MemoryView(id, value, expectedVersion + 1, existing.createdAt(), now);
        });
    }

    public void delete(Actor actor, long id, long expectedVersion) {
        version(expectedVersion);
        transactions.executeWithoutResult(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            AssistantAccess.current(accounts, current);
            MemoryView existing = memory(current.id(), id);
            if (existing.version() != expectedVersion || jdbc.update("DELETE FROM sc_assistant_memory WHERE id=? AND account_id=? AND version=?", id, current.id(), expectedVersion) != 1) throw stale();
        });
    }

    public void clear(Actor actor) {
        transactions.executeWithoutResult(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            AssistantAccess.current(accounts, current);
            jdbc.update("DELETE FROM sc_assistant_memory WHERE account_id=?", current.id());
        });
    }

    public MemorySettings enable(Actor actor, boolean enabled) {
        return transactions.execute(status -> {
            Actor current = AssistantAccess.current(accounts, actor);
            AssistantAccess.ownerLock(jdbc, current.id());
            AssistantAccess.current(accounts, current);
            List<Long> present = jdbc.queryForList("SELECT account_id FROM sc_assistant_memory_settings WHERE account_id=?", Long.class, current.id());
            if (present.isEmpty()) jdbc.update("INSERT INTO sc_assistant_memory_settings(account_id,enabled) VALUES(?,?)", current.id(), enabled);
            else jdbc.update("UPDATE sc_assistant_memory_settings SET enabled=? WHERE account_id=?", enabled, current.id());
            return settings(current.id());
        });
    }

    /** Bounded lexical recall; disabled memory produces no context, but its owner may still edit it. */
    public List<MemoryView> recall(Actor actor, String query, int limit) {
        long id = AssistantAccess.current(accounts, actor).id();
        if (limit < 1 || limit > 10) throw AssistantAccess.fault("41000", 400, "记忆召回数量须在 1 到 10 之间");
        String wanted = AssistantAccess.text(query, 500, false).toLowerCase(Locale.ROOT);
        if (!settings(id).enabled()) return List.of();
        List<String> terms = java.util.Arrays.stream(wanted.split("\\s+")).distinct().limit(8).toList();
        return rows(id).stream().filter(memory -> terms.stream().anyMatch(term -> memory.content().toLowerCase(Locale.ROOT).contains(term))).limit(limit).toList();
    }

    private List<MemoryView> rows(long owner) {
        return jdbc.query("SELECT id,content,version,created_at,updated_at FROM sc_assistant_memory WHERE account_id=? ORDER BY updated_at DESC,id DESC LIMIT 50",
                (r, index) -> new MemoryView(r.getLong("id"), r.getString("content"), r.getLong("version"), r.getLong("created_at"), r.getLong("updated_at")), owner);
    }

    private MemoryView memory(long owner, long id) {
        List<MemoryView> found = jdbc.query("SELECT id,content,version,created_at,updated_at FROM sc_assistant_memory WHERE id=? AND account_id=?",
                (r, index) -> new MemoryView(r.getLong("id"), r.getString("content"), r.getLong("version"), r.getLong("created_at"), r.getLong("updated_at")), id, owner);
        if (found.size() != 1) throw AssistantAccess.fault("40400", 404, "记忆不存在");
        return found.getFirst();
    }

    private long count(long owner) { return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_assistant_memory WHERE account_id=?", Long.class, owner)); }
    private MemorySettings settings(long owner) {
        List<Boolean> values = jdbc.queryForList("SELECT enabled FROM sc_assistant_memory_settings WHERE account_id=?", Boolean.class, owner);
        return new MemorySettings(values.isEmpty() || Boolean.TRUE.equals(values.getFirst()), count(owner));
    }
    private static void version(long version) { if (version < 1 || version == Long.MAX_VALUE) throw AssistantAccess.fault("41000", 400, "记忆版本无效"); }
    private static io.studentcloud.common.ApplicationFault stale() { return AssistantAccess.fault("41000", 409, "记忆已变化，请刷新后再编辑"); }
}
