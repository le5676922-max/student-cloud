package im.tlgxy.cloud.module.support;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.service.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Account-scoped support inbox. Messages are plain text and persisted before notification. */
@Service
public class SupportService {
    public static final int MAX_CONTENT = 2000;
    private static final Set<String> STATUSES = Set.of("WAITING", "ACTIVE", "CLOSED");
    private static final Pattern CONVERSATION_ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    // Locks contain no identities or messages, and have a fixed memory bound. Database
    // uniqueness and a row-write barrier remain authoritative across application instances.
    private final Object[] writeStripes = new Object[128];

    public SupportService(JdbcTemplate jdbc, UserService users, ApplicationEventPublisher events,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        for (int index = 0; index < writeStripes.length; index++) writeStripes[index] = new Object();
    }

    public record ConversationView(String id, int ownerUserId, String ownerAccountId, String ownerName,
                                   Integer tenantId, String tenantName, String subject, String status,
                                   long createdAt, long updatedAt) { }
    public record MessageView(long id, String conversationId, int senderUserId, String senderRole,
                              String content, String clientMessageId, long createdAt) { }
    public record Page(List<ConversationView> items, long total, int page, int pageSize) { }
    public record MessagesPage(List<MessageView> items, boolean hasMore) { }

    private static final String CONVERSATION_SELECT = "SELECT c.id,c.owner_user_id,u.account_id,u.nickname,u.username,"
            + "c.tenant_id,t.name AS tenant_name,c.subject,c.status,c.created_at,c.updated_at "
            + "FROM support_conversation c JOIN `user` u ON u.id=c.owner_user_id LEFT JOIN tenant t ON t.id=c.tenant_id ";
    private static final RowMapper<ConversationView> CONVERSATION_ROW = (row, index) -> new ConversationView(
            row.getString("id"), row.getInt("owner_user_id"), row.getString("account_id"),
            row.getString("nickname") == null || row.getString("nickname").isBlank() ? row.getString("username") : row.getString("nickname"),
            row.getObject("tenant_id") == null ? null : row.getInt("tenant_id"), row.getString("tenant_name"),
            row.getString("subject"), row.getString("status"), row.getLong("created_at"), row.getLong("updated_at"));
    private static final RowMapper<MessageView> MESSAGE_ROW = (row, index) -> new MessageView(
            row.getLong("id"), row.getString("conversation_id"), row.getInt("sender_user_id"),
            row.getString("sender_role"), row.getString("content"), row.getString("client_message_id"), row.getLong("created_at"));

    public Page list(int actorId, int page, int pageSize, String status) {
        User actor = actor(actorId);
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 50) throw new BizException("客服列表分页参数不正确");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw new BizException("客服会话状态不正确");
        List<Object> values = new ArrayList<>();
        String filter = " WHERE 1=1";
        if (!admin(actor)) { filter += " AND c.owner_user_id=?"; values.add(actorId); }
        if (status != null && !status.isBlank()) { filter += " AND c.status=?"; values.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation c" + filter, Long.class, values.toArray());
        values.add(pageSize);
        values.add((page - 1) * pageSize);
        return new Page(jdbc.query(CONVERSATION_SELECT + filter + " ORDER BY c.updated_at DESC,c.id DESC LIMIT ? OFFSET ?",
                CONVERSATION_ROW, values.toArray()), total == null ? 0 : total, page, pageSize);
    }

    public ConversationView get(int actorId, String id) {
        return requireAccess(actor(actorId), id);
    }

    public ConversationView open(int actorId, String content, String clientMessageId) {
        User actor = actor(actorId);
        if (admin(actor)) throw new BizException("全局管理员请在客服控制台接待成员会话");
        String text = content(content);
        String clientId = clientId(clientMessageId);
        synchronized (stripe(actorId)) {
            // A unique active_owner_id settles simultaneous opens, including another backend.
            try { return transaction(() -> openInside(actorId, text, clientId)); }
            catch (DuplicateKeyException conflict) {
                return transaction(() -> openInside(actorId, text, clientId));
            }
        }
    }

    private ConversationView openInside(int actorId, String text, String clientId) {
        User actor = actor(actorId);
        if (admin(actor)) throw new BizException("全局管理员请在客服控制台接待成员会话");
        MessageView previous = previous(actorId, clientId);
        if (previous != null) {
            ConversationView conversation = requireAccess(actor, previous.conversationId());
            if (!previous.content().equals(text) || conversation.ownerUserId() != actorId) throw idConflict();
            return conversation;
        }
        List<ConversationView> active = jdbc.query(CONVERSATION_SELECT + " WHERE c.active_owner_id=?", CONVERSATION_ROW, actorId);
        String id;
        if (active.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO support_conversation (id,owner_user_id,active_owner_id,tenant_id,subject,status,created_at,updated_at) VALUES (?,?,?,?,?,'WAITING',?,?)",
                    id, actorId, actorId, actor.getTenantId(), text.substring(0, Math.min(100, text.length())), now, now);
        } else id = active.getFirst().id();
        lockConversation(id);
        ConversationView conversation = requireAccess(actor(actorId), id);
        if ("CLOSED".equals(conversation.status())) throw new BizException("会话已结束，请刷新后重新发起客服请求");
        insertMessage(actor, conversation, text, clientId);
        return find(id);
    }

    public MessagesPage messages(int actorId, String id, Long afterId, Long beforeId, int limit) {
        requireAccess(actor(actorId), id);
        if (limit < 1 || limit > 100 || (afterId != null && afterId < 0) || (beforeId != null && beforeId <= 0)
                || (afterId != null && beforeId != null)) throw new BizException("客服消息分页参数不正确");
        List<Object> values = new ArrayList<>(); values.add(id);
        String filter = " WHERE conversation_id=?";
        boolean ascending = afterId != null;
        if (afterId != null) { filter += " AND id>?"; values.add(afterId); }
        if (beforeId != null) { filter += " AND id<?"; values.add(beforeId); }
        values.add(limit + 1);
        List<MessageView> items = new ArrayList<>(jdbc.query("SELECT * FROM support_message" + filter
                + (ascending ? " ORDER BY id ASC LIMIT ?" : " ORDER BY id DESC LIMIT ?"), MESSAGE_ROW, values.toArray()));
        boolean hasMore = items.size() > limit;
        if (hasMore) items.removeLast();
        if (!ascending) Collections.reverse(items);
        return new MessagesPage(List.copyOf(items), hasMore);
    }

    public MessageView send(int actorId, String id, String content, String clientMessageId) {
        User actor = actor(actorId);
        ConversationView initial = requireAccess(actor, id);
        String text = content(content);
        String clientId = clientId(clientMessageId);
        synchronized (stripe(initial.ownerUserId())) {
            try { return transaction(() -> sendInside(actorId, id, text, clientId)); }
            catch (DuplicateKeyException conflict) {
                // A simultaneous retry may have committed the same idempotent message.
                return transaction(() -> sendInside(actorId, id, text, clientId));
            }
        }
    }

    private MessageView sendInside(int actorId, String id, String text, String clientId) {
        lockConversation(id);
        User actor = actor(actorId);
        ConversationView conversation = requireAccess(actor, id);
        MessageView previous = previous(actorId, clientId);
        if (previous != null) {
            if (!previous.conversationId().equals(id) || !previous.content().equals(text)) throw idConflict();
            return previous;
        }
        if ("CLOSED".equals(conversation.status())) throw new BizException("客服会话已结束，请重新发起会话");
        return insertMessage(actor, conversation, text, clientId);
    }

    public ConversationView accept(int actorId, String id) {
        User actor = actor(actorId);
        if (!admin(actor)) throw new BizException("仅全局管理员可接待客服会话");
        ConversationView initial = requireAccess(actor, id);
        synchronized (stripe(initial.ownerUserId())) {
            return transaction(() -> {
                lockConversation(id);
                User current = actor(actorId);
                if (!admin(current)) throw new BizException("仅全局管理员可接待客服会话");
                ConversationView conversation = requireAccess(current, id);
                if ("CLOSED".equals(conversation.status())) throw new BizException("客服会话已结束");
                if ("WAITING".equals(conversation.status())) {
                    jdbc.update("UPDATE support_conversation SET status='ACTIVE',updated_at=? WHERE id=?", System.currentTimeMillis(), id);
                    notifyAfterCommit(conversation);
                }
                return find(id);
            });
        }
    }

    public ConversationView close(int actorId, String id) {
        ConversationView initial = requireAccess(actor(actorId), id);
        synchronized (stripe(initial.ownerUserId())) {
            return transaction(() -> {
                lockConversation(id);
                ConversationView conversation = requireAccess(actor(actorId), id);
                if (!"CLOSED".equals(conversation.status())) {
                    jdbc.update("UPDATE support_conversation SET status='CLOSED',active_owner_id=NULL,updated_at=? WHERE id=?", System.currentTimeMillis(), id);
                    notifyAfterCommit(conversation);
                }
                return find(id);
            });
        }
    }

    /** The foreign keys cover normal databases; retain explicit cleanup for legacy SQLite setups. */
    @EventListener
    public void onUserDeleted(UserDeleteEvent event) {
        jdbc.update("DELETE FROM support_message WHERE conversation_id IN (SELECT id FROM support_conversation WHERE owner_user_id=?)", event.getId());
        jdbc.update("DELETE FROM support_conversation WHERE owner_user_id=?", event.getId());
    }

    private MessageView insertMessage(User actor, ConversationView conversation, String text, String clientId) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO support_message (conversation_id,sender_user_id,sender_role,content,client_message_id,created_at) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, conversation.id()); statement.setInt(2, actor.getId()); statement.setString(3, actor.getRole().name());
            statement.setString(4, text); statement.setString(5, clientId); statement.setLong(6, now);
            return statement;
        }, key);
        String status = admin(actor) && "WAITING".equals(conversation.status()) ? "ACTIVE" : conversation.status();
        jdbc.update("UPDATE support_conversation SET status=?,updated_at=? WHERE id=?", status, now, conversation.id());
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Support message ID was not generated");
        MessageView message = new MessageView(generated.longValue(), conversation.id(), actor.getId(), actor.getRole().name(), text, clientId, now);
        notifyAfterCommit(conversation);
        return message;
    }

    private void lockConversation(String id) {
        validateId(id);
        jdbc.update("UPDATE support_conversation SET updated_at=updated_at WHERE id=?", id);
        // Drivers configured with useAffectedRows may report zero for the no-op
        // write even when it acquired the existing row lock.
        if (find(id) == null) throw notFound();
    }

    private MessageView previous(int actorId, String clientId) {
        List<MessageView> existing = jdbc.query("SELECT * FROM support_message WHERE sender_user_id=? AND client_message_id=?", MESSAGE_ROW, actorId, clientId);
        return existing.isEmpty() ? null : existing.getFirst();
    }

    private ConversationView requireAccess(User actor, String id) {
        validateId(id);
        ConversationView conversation = find(id);
        if (conversation == null || (!admin(actor) && conversation.ownerUserId() != actor.getId())) throw notFound();
        return conversation;
    }

    private ConversationView find(String id) {
        List<ConversationView> rows = jdbc.query(CONVERSATION_SELECT + " WHERE c.id=?", CONVERSATION_ROW, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private User actor(int actorId) {
        User actor = users.requireLoginUser(actorId);
        if (actor == null || actor.getId() == null || actor.getId() != actorId || actorId <= 2 && actorId != 1
                || !Boolean.TRUE.equals(actor.getEnable()) || actor.getRole() == null || !actor.getRole().isLoginRole()) throw new BizException("请使用有效账号登录客服");
        return actor;
    }

    private void notifyAfterCommit(ConversationView conversation) {
        SupportChangedEvent event = new SupportChangedEvent(conversation.id(), conversation.ownerUserId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.publishEvent(event); }
                catch (RuntimeException deliveryFailure) {
                    // Persistence already committed. Do not report a successful write as a failure.
                    org.slf4j.LoggerFactory.getLogger(SupportService.class)
                            .warn("Support notification failed; committed messages remain available from history");
                }
            }
        });
    }

    private <T> T transaction(Supplier<T> callback) { return transactions.execute(status -> callback.get()); }
    private Object stripe(int owner) { return writeStripes[Math.floorMod(owner, writeStripes.length)]; }
    private static boolean admin(User actor) { return actor.getRole() == UserRoleEnum.ADMIN; }
    private static String content(String input) {
        if (input == null || input.isBlank() || input.length() > MAX_CONTENT) throw new BizException("消息需为1至2000个字符");
        String text = input.trim();
        if (text.codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) throw new BizException("消息包含不支持的控制字符");
        return text;
    }
    private static String clientId(String input) {
        if (input == null || !CLIENT_ID.matcher(input).matches()) throw new BizException("消息请求标识不正确");
        return input;
    }
    private static void validateId(String id) { if (id == null || !CONVERSATION_ID.matcher(id).matches()) throw notFound(); }
    private static BizException notFound() { return new BizException("客服会话不存在或无权访问"); }
    private static BizException idConflict() { return new BizException("消息请求标识已用于其他内容，请刷新后重试"); }
}
