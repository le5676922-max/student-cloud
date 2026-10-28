package io.studentcloud.support;

import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit user-requested human service, backed by the independent database.
 * No AI call, automatic handoff, chat-history copy, WebSocket, or background poller is started.
 * Trusted HTTP adapters supply Actor; every method rechecks the current database identity.
 */
public final class SupportService {
    private static final String CONVERSATION_COLUMNS = "conversation_id,requester_id,workspace_id,subject,state,assigned_admin_id,created_at,updated_at,closed_at";
    private static final RowMapper<SupportConversation> CONVERSATION = (row, index) -> new SupportConversation(
            row.getString("conversation_id"), row.getLong("requester_id"), row.getLong("workspace_id"), row.getString("subject"),
            SupportState.valueOf(row.getString("state")), nullableLong(row, "assigned_admin_id"),
            row.getLong("created_at"), row.getLong("updated_at"), nullableLong(row, "closed_at"));
    private static final RowMapper<SupportMessage> MESSAGE = (row, index) -> new SupportMessage(
            row.getString("message_id"), row.getString("conversation_id"), row.getLong("message_no"), row.getLong("sender_id"),
            Role.valueOf(row.getString("sender_role")), row.getString("client_message_id"), row.getString("body"), row.getLong("created_at"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SupportService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
        this.clock = Objects.requireNonNull(clock);
    }

    /** An explicit request only sends the subject and message the user selected to send. */
    public SupportConversation request(Actor actor, String subject, String openingMessage) {
        return request(actor, subject, openingMessage, "new-" + UUID.randomUUID());
    }

    /** Retry of the same explicit opening request returns its existing conversation. */
    public SupportConversation request(Actor actor, String subject, String openingMessage, String clientMessageId) {
        String title = normalizedText(subject, 160, false, "问题标题须为 1 至 160 字符");
        String body = message(openingMessage);
        if (clientMessageId == null || !clientMessageId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ApplicationFault("41000", 400, "消息标识格式不正确");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount current = requireCurrent(actor);
            if (current.role() == Role.ADMIN) throw forbidden();
            jdbc.queryForObject("SELECT id FROM sc_account WHERE id=? FOR UPDATE", Long.class, current.id());
            current = requireCurrent(actor);
            String requestKey = openingHash(current.id() + "\n" + clientMessageId);
            String payloadHash = openingHash(title + "\n" + body);
            var existing = jdbc.query("SELECT payload_hash,conversation_id FROM sc_support_open_request WHERE request_key=?",
                    (row, ignored) -> new String[]{row.getString(1), row.getString(2)}, requestKey);
            if (!existing.isEmpty()) {
                if (!existing.getFirst()[0].equals(payloadHash)) throw new ApplicationFault("41000", 409, "同一消息标识不能用于不同内容");
                return conversation(current, existing.getFirst()[1], false);
            }
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_conversation WHERE requester_id=? AND state<>'CLOSED'", Long.class, current.id());
            if (active == null || active >= 10) throw new ApplicationFault("41000", 409, "请先结束已有人工服务会话");
            long now = clock.millis();
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sc_support_conversation (conversation_id,requester_id,workspace_id,subject,state,last_message_no,created_at,updated_at) "
                            + "VALUES (?,?,?,?,'WAITING',1,?,?)", id, current.id(), current.workspaceId(), title, now, now);
            insertMessage(id, 1, current, "opening-" + UUID.randomUUID(), body, now);
            jdbc.update("INSERT INTO sc_support_open_request(request_key,payload_hash,conversation_id) VALUES(?,?,?)", requestKey, payloadHash, id);
            return conversation(current, id, false);
        }));
    }

    public SupportConversation closeForRequester(Actor actor, String id) {
        requireId(id);
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount current = requireCurrent(actor);
            SupportConversation found = conversation(current, id, true);
            if (found.requesterId() != current.id()) throw forbidden();
            if (found.state() == SupportState.CLOSED) return found;
            long now = clock.millis();
            jdbc.update("UPDATE sc_support_conversation SET state='CLOSED',closed_at=?,updated_at=? WHERE conversation_id=?", now, now, id);
            return conversation(current, id, false);
        }));
    }

    private static String openingHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Request hashing is unavailable", unavailable); }
    }

    /** Ordinary administrators see their own conversations, never their workspace members' cases. */
    public List<SupportConversation> list(Actor actor, int offset, int limit) {
        return list(actor, offset, limit, null);
    }

    public List<SupportConversation> list(Actor actor, int offset, int limit, SupportState state) {
        page(offset, limit);
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount current = requireCurrent(actor);
            String query = "SELECT " + CONVERSATION_COLUMNS + " FROM sc_support_conversation WHERE 1=1";
            if (current.role() == Role.ADMIN) {
                if (state == null) return jdbc.query(query + " ORDER BY updated_at DESC,conversation_id DESC LIMIT ? OFFSET ?", CONVERSATION, limit, offset);
                return jdbc.query(query + " AND state=? ORDER BY updated_at DESC,conversation_id DESC LIMIT ? OFFSET ?", CONVERSATION, state.name(), limit, offset);
            }
            if (state == null) return jdbc.query(query + " AND requester_id=? ORDER BY updated_at DESC,conversation_id DESC LIMIT ? OFFSET ?",
                    CONVERSATION, current.id(), limit, offset);
            return jdbc.query(query + " AND requester_id=? AND state=? ORDER BY updated_at DESC,conversation_id DESC LIMIT ? OFFSET ?",
                    CONVERSATION, current.id(), state.name(), limit, offset);
        }));
    }

    public long count(Actor actor) { return count(actor, null); }

    public long count(Actor actor, SupportState state) {
        Long result = transactions.execute(status -> {
            CurrentAccount current = requireCurrent(actor);
            if (current.role() == Role.ADMIN) {
                return state == null ? jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_conversation", Long.class)
                        : jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_conversation WHERE state=?", Long.class, state.name());
            }
            return state == null ? jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_conversation WHERE requester_id=?", Long.class, current.id())
                    : jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_conversation WHERE requester_id=? AND state=?", Long.class, current.id(), state.name());
        });
        return Objects.requireNonNull(result);
    }

    public SupportConversation get(Actor actor, String id) {
        requireId(id);
        return Objects.requireNonNull(transactions.execute(status -> conversation(requireCurrent(actor), id, false)));
    }

    public List<SupportMessage> messages(Actor actor, String id, int offset, int limit) {
        requireId(id);
        page(offset, limit);
        return Objects.requireNonNull(transactions.execute(status -> {
            conversation(requireCurrent(actor), id, false);
            return jdbc.query("SELECT message_id,conversation_id,message_no,sender_id,sender_role,client_message_id,body,created_at "
                            + "FROM sc_support_message WHERE conversation_id=? ORDER BY message_no LIMIT ? OFFSET ?", MESSAGE, id, limit, offset);
        }));
    }

    public long messageCount(Actor actor, String id) {
        requireId(id);
        Long result = transactions.execute(status -> {
            conversation(requireCurrent(actor), id, false);
            return jdbc.queryForObject("SELECT COUNT(*) FROM sc_support_message WHERE conversation_id=?", Long.class, id);
        });
        return Objects.requireNonNull(result);
    }

    /** First successful acceptance owns the conversation; repeating it by that admin is idempotent. */
    public SupportConversation accept(Actor actor, String id) {
        requireId(id);
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount administrator = requireCurrent(actor);
            requireAdministrator(administrator);
            SupportConversation current = conversation(administrator, id, true);
            if (current.state() == SupportState.CLOSED) throw closed();
            if (current.state() == SupportState.ACTIVE) {
                if (!Objects.equals(current.assignedAdminId(), administrator.id())) throw assigned();
                return current;
            }
            if (jdbc.update("UPDATE sc_support_conversation SET state='ACTIVE',assigned_admin_id=?,updated_at=? "
                            + "WHERE conversation_id=? AND state='WAITING' AND assigned_admin_id IS NULL", administrator.id(), clock.millis(), id) != 1)
                throw assigned();
            return conversation(administrator, id, false);
        }));
    }

    /** Idempotency is scoped to one conversation and sender; retries after close return the old message. */
    public SupportMessage reply(Actor actor, String id, String clientMessageId, String body) {
        requireId(id);
        requireClientId(clientMessageId);
        String content = message(body);
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount sender = requireCurrent(actor);
            SupportConversation current = conversation(sender, id, true);
            if (sender.role() == Role.ADMIN && !Objects.equals(current.assignedAdminId(), sender.id())) throw assigned();
            String key = hash(sender.id() + "\n" + clientMessageId);
            List<StoredMessage> existing = jdbc.query("SELECT message_id,conversation_id,message_no,sender_id,sender_role,client_message_id,body,body_digest,created_at "
                            + "FROM sc_support_message WHERE conversation_id=? AND idempotency_hash=?",
                    (row, index) -> new StoredMessage(MESSAGE.mapRow(row, index), row.getString("body_digest")), id, key);
            if (!existing.isEmpty()) {
                if (!hash(content).equals(existing.getFirst().digest()))
                    throw new ApplicationFault("SUPPORT_MESSAGE_CONFLICT", 409, "同一个消息标识不能发送不同内容");
                return existing.getFirst().message();
            }
            if (current.state() == SupportState.CLOSED) throw closed();
            if (sender.role() == Role.ADMIN && current.state() != SupportState.ACTIVE) throw assigned();
            Long last = jdbc.queryForObject("SELECT last_message_no FROM sc_support_conversation WHERE conversation_id=?", Long.class, id);
            long sequence = Math.addExact(Objects.requireNonNull(last), 1L);
            long now = clock.millis();
            SupportMessage result = insertMessage(id, sequence, sender, clientMessageId, content, now);
            if (jdbc.update("UPDATE sc_support_conversation SET last_message_no=?,updated_at=? WHERE conversation_id=? AND last_message_no=?",
                    sequence, now, id, last) != 1) throw assigned();
            return result;
        }));
    }

    /** Only a global admin closes a selected case; an active case belongs to its accepting admin. */
    public SupportConversation close(Actor actor, String id) {
        requireId(id);
        return Objects.requireNonNull(transactions.execute(status -> {
            CurrentAccount administrator = requireCurrent(actor);
            requireAdministrator(administrator);
            SupportConversation current = conversation(administrator, id, true);
            if (current.state() == SupportState.CLOSED) return current;
            if (current.state() == SupportState.ACTIVE && !Objects.equals(current.assignedAdminId(), administrator.id())) throw assigned();
            long now = clock.millis();
            jdbc.update("UPDATE sc_support_conversation SET state='CLOSED',updated_at=?,closed_at=?,closed_by=? WHERE conversation_id=?",
                    now, now, administrator.id(), id);
            return conversation(administrator, id, false);
        }));
    }

    private SupportConversation conversation(CurrentAccount actor, String id, boolean lock) {
        String query = "SELECT " + CONVERSATION_COLUMNS + " FROM sc_support_conversation WHERE conversation_id=?";
        String suffix = lock ? " FOR UPDATE" : "";
        List<SupportConversation> rows = actor.role() == Role.ADMIN
                ? jdbc.query(query + suffix, CONVERSATION, id)
                : jdbc.query(query + " AND requester_id=?" + suffix, CONVERSATION, id, actor.id());
        if (rows.size() != 1) throw notFound();
        return rows.getFirst();
    }

    private SupportMessage insertMessage(String conversation, long sequence, CurrentAccount sender, String clientMessageId, String body, long now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sc_support_message (message_id,conversation_id,message_no,sender_id,sender_role,client_message_id,"
                        + "idempotency_hash,body_digest,body,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, conversation, sequence, sender.id(), sender.role().name(), clientMessageId,
                hash(sender.id() + "\n" + clientMessageId), hash(body), body, now);
        return new SupportMessage(id, conversation, sequence, sender.id(), sender.role(), clientMessageId, body, now);
    }

    private CurrentAccount requireCurrent(Actor actor) {
        if (actor == null || actor.id() <= 0) throw unauthorized();
        List<CurrentAccount> rows = jdbc.query("SELECT id,role,workspace_id,enabled,session_version FROM sc_account WHERE id=? FOR UPDATE",
                (row, index) -> {
                    if (!row.getBoolean("enabled") || row.getLong("session_version") < 1) throw unauthorized();
                    Role role;
                    try { role = Role.valueOf(row.getString("role")); }
                    catch (IllegalArgumentException | NullPointerException invalid) { throw unauthorized(); }
                    return new CurrentAccount(row.getLong("id"), role, nullableLong(row, "workspace_id"));
                }, actor.id());
        if (rows.size() != 1) throw unauthorized();
        CurrentAccount current = rows.getFirst();
        if (current.role() != actor.role() || !Objects.equals(current.workspaceId(), actor.workspaceId())) throw unauthorized();
        if (current.role() == Role.ADMIN) {
            if (current.workspaceId() != null) throw unauthorized();
            return current;
        }
        if (current.workspaceId() == null || current.workspaceId() <= 0) throw unauthorized();
        List<Boolean> flags = jdbc.queryForList("SELECT enabled FROM sc_workspace WHERE id=? FOR UPDATE", Boolean.class, current.workspaceId());
        if (flags.size() != 1 || !Boolean.TRUE.equals(flags.getFirst())) throw unauthorized();
        return current;
    }

    private static String message(String body) {
        return normalizedText(body, 4000, true, "消息须为 1 至 4000 字符");
    }

    private static String normalizedText(String value, int maximum, boolean multiline, String message) {
        if (value == null || value.length() > maximum * 2 + 20 || value.isBlank()) throw invalid(message);
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) throw invalid(message);
        String result = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (result.isBlank() || result.codePointCount(0, result.length()) > maximum) throw invalid(message);
        return result;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw notFound();
    }
    private static void requireClientId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) throw invalid("消息标识格式不正确");
    }
    private static void requireAdministrator(CurrentAccount actor) { if (actor.role() != Role.ADMIN) throw forbidden(); }
    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid("分页参数不正确");
    }
    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long result = row.getLong(column);
        return row.wasNull() ? null : result;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static ApplicationFault unauthorized() { return new ApplicationFault("42000", 401, "请重新登录"); }
    private static ApplicationFault forbidden() { return new ApplicationFault("30000", 403, "此操作需要全局管理员接待权限"); }
    private static ApplicationFault notFound() { return new ApplicationFault("SUPPORT_NOT_FOUND", 404, "会话不存在或无权访问"); }
    private static ApplicationFault assigned() { return new ApplicationFault("SUPPORT_ASSIGNED", 409, "请先接待此会话，或联系已接待的管理员处理"); }
    private static ApplicationFault closed() { return new ApplicationFault("SUPPORT_CLOSED", 409, "此会话已结束，请新建服务申请"); }
    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private record CurrentAccount(long id, Role role, Long workspaceId) { }
    private record StoredMessage(SupportMessage message, String digest) { }
}
