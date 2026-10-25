package im.tlgxy.cloud.module.support;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.event.UserDeleteEvent;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real disposable SQLite transactions; no business database, network or external model. */
class SupportServiceTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private SupportService support;
    private UserService users;
    private final Map<Integer, User> actors = new HashMap<>();
    private final List<SupportChangedEvent> notifications = new ArrayList<>();

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("support.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        Properties sqlite = new Properties(); sqlite.setProperty("foreign_keys", "true");
        source.setConnectionProperties(sqlite);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("CREATE TABLE `user` (id INTEGER PRIMARY KEY,account_id TEXT,username TEXT,nickname TEXT)");
        jdbc.execute("CREATE TABLE tenant (id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.update("INSERT INTO tenant VALUES (4,'Space A'),(5,'Space B')");
        for (var actor : List.of(actor(1, UserRoleEnum.ADMIN, null), actor(10, UserRoleEnum.USER, 4),
                actor(11, UserRoleEnum.USER, 5), actor(12, UserRoleEnum.TENANT_ADMIN, 4))) {
            actors.put(actor.getId(), actor);
            jdbc.update("INSERT INTO `user` VALUES (?,?,?,?)", actor.getId(), actor.getAccountId(), actor.getUsername(), actor.getNickname());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V74__human_support_conversations.sql")).execute(source);
        users = mock(UserService.class);
        when(users.requireLoginUser(anyInt())).thenAnswer(call -> {
            User user = actors.get(call.getArgument(0));
            if (user == null || !Boolean.TRUE.equals(user.getEnable())) throw new BizException("账号已停用");
            return user;
        });
        ApplicationEventPublisher events = event -> {
            if (event instanceof SupportChangedEvent changed) {
                // Do not reuse the transaction-bound connection when proving commit visibility.
                assertDoesNotThrow(() -> {
                    try (var connection = source.getConnection(); var query = connection.prepareStatement("SELECT status FROM support_conversation WHERE id=?")) {
                        query.setString(1, changed.conversationId());
                        try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
                    }
                });
                notifications.add(changed);
            }
        };
        support = new SupportService(jdbc, users, events, new DataSourceTransactionManager(source));
    }

    @AfterEach void noGlobalState() { actors.clear(); notifications.clear(); }

    @Test void membersOnlyReadTheirOwnConversationAndGlobalAdministratorSeesTheQueue() {
        var first = support.open(10, "AI could not solve my upload issue", "open-a");
        var second = support.open(11, "Space B question", "open-b");
        assertEquals(10, first.ownerUserId());
        assertEquals("WAITING", first.status());
        assertEquals(4, first.tenantId());
        assertEquals("Space A", first.tenantName());
        assertEquals(1, support.list(10, 1, 20, null).total());
        assertEquals(2, support.list(1, 1, 20, null).total());
        assertThrows(BizException.class, () -> support.get(11, first.id()));
        assertThrows(BizException.class, () -> support.messages(11, first.id(), null, null, 50));
        assertThrows(BizException.class, () -> support.send(11, first.id(), "foreign write", "foreign-a"));
        assertThrows(BizException.class, () -> support.close(11, first.id()));
        assertThrows(BizException.class, () -> support.get(12, first.id()), "workspace managers cannot read another account's support history");
        assertEquals(second.id(), support.get(1, second.id()).id());
    }

    @Test void noGlobalAdministratorSelfRequestsAndOnlyGlobalAdministratorCanAccept() {
        assertThrows(BizException.class, () -> support.open(1, "self", "admin-open"));
        var first = support.open(10, "my issue", "member-open");
        assertThrows(BizException.class, () -> support.accept(10, first.id()));
        assertThrows(BizException.class, () -> support.accept(12, first.id()));
        assertEquals("ACTIVE", support.accept(1, first.id()).status());
        int delivered = notifications.size();
        assertEquals("ACTIVE", support.accept(1, first.id()).status());
        assertEquals(delivered, notifications.size(), "accepting an already active conversation is not a new mutation");
    }

    @Test void openRetriesAndAdditionalRequestsReuseOneActiveConversationWithoutDroppingMessages() {
        var first = support.open(10, "Initial problem", "request-a");
        assertEquals(first.id(), support.open(10, "Initial problem", "request-a").id());
        assertEquals(first.id(), support.open(10, "Additional context", "request-b").id());
        assertEquals(1, support.list(10, 1, 20, null).total());
        assertEquals(2, support.messages(10, first.id(), null, null, 50).items().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE active_owner_id=10", Integer.class));
        assertThrows(BizException.class, () -> support.open(10, "Different content", "request-a"));
    }

    @Test void administratorReplyIsPersistentAndAutomaticallyActivatesWaitingConversation() {
        var first = support.open(10, "Help", "request-a");
        var reply = support.send(1, first.id(), "Please check the selected storage source.", "reply-a");
        assertEquals("ADMIN", reply.senderRole());
        assertEquals(1, reply.senderUserId());
        assertEquals("ACTIVE", support.get(10, first.id()).status());
        assertEquals(reply.id(), support.messages(10, first.id(), null, null, 50).items().getLast().id());
        var restored = new SupportService(jdbc, users, event -> {}, new DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(reply.content(), restored.messages(10, first.id(), null, null, 50).items().getLast().content());
        assertEquals(10, notifications.getLast().ownerUserId());
        assertEquals(first.id(), notifications.getLast().conversationId());
    }

    @Test void idempotencyIsPerSenderAndCannotBeReusedForAnotherMessageOrConversation() {
        var first = support.open(10, "Help", "request-a");
        var sent = support.send(10, first.id(), "more", "same-request");
        assertEquals(sent.id(), support.send(10, first.id(), "more", "same-request").id());
        assertThrows(BizException.class, () -> support.send(10, first.id(), "changed", "same-request"));
        assertNotEquals(sent.id(), support.send(1, first.id(), "admin text", "same-request").id());
        support.close(10, first.id());
        var second = support.open(10, "Another problem", "request-b");
        assertThrows(BizException.class, () -> support.send(10, second.id(), "more", "same-request"));
        assertEquals(1, support.messages(10, second.id(), null, null, 50).items().size());
    }

    @Test void closingRetainsHistoryRejectsNewMessagesAndAllowsANewRequest() {
        var first = support.open(10, "Help", "request-a");
        var sent = support.send(10, first.id(), "more", "message-a");
        assertEquals("CLOSED", support.close(1, first.id()).status());
        assertNull(jdbc.queryForObject("SELECT active_owner_id FROM support_conversation WHERE id=?", Integer.class, first.id()));
        assertEquals(sent.id(), support.send(10, first.id(), "more", "message-a").id());
        assertThrows(BizException.class, () -> support.send(10, first.id(), "new message", "message-b"));
        assertThrows(BizException.class, () -> support.accept(1, first.id()));
        var next = support.open(10, "New issue", "request-b");
        assertNotEquals(first.id(), next.id());
        assertEquals(2, support.list(10, 1, 20, null).total());
        assertEquals(1, support.list(10, 1, 20, "CLOSED").total());
        assertEquals(first.id(), support.open(10, "Help", "request-a").id(), "retrying a committed old request does not create another conversation");
    }

    @Test void historyHasBoundedLatestEarlierAndIncrementalPagesInAscendingOrder() {
        var first = support.open(10, "first", "request-a");
        for (int index = 1; index < 6; index++) support.send(10, first.id(), "Message " + index, "message-" + index);
        var latest = support.messages(10, first.id(), null, null, 2);
        assertEquals(List.of("Message 4", "Message 5"), latest.items().stream().map(SupportService.MessageView::content).toList());
        assertTrue(latest.hasMore());
        var earlier = support.messages(10, first.id(), null, latest.items().getFirst().id(), 2);
        assertEquals(List.of("Message 2", "Message 3"), earlier.items().stream().map(SupportService.MessageView::content).toList());
        var delta = support.messages(10, first.id(), earlier.items().getLast().id(), null, 2);
        assertEquals(latest.items(), delta.items());
        assertFalse(delta.hasMore());
        assertTrue(support.messages(10, first.id(), latest.items().getLast().id(), null, 50).items().isEmpty());
    }

    @Test void validationRejectsUnboundedOrAmbiguousRequestsBeforeWriting() {
        assertThrows(BizException.class, () -> support.open(10, " ", "a"));
        assertThrows(BizException.class, () -> support.open(10, "x".repeat(2001), "a"));
        assertThrows(BizException.class, () -> support.open(10, "contains\u0000control", "a"));
        assertThrows(BizException.class, () -> support.open(10, "ok", "../../bad"));
        assertThrows(BizException.class, () -> support.list(10, 0, 20, null));
        assertThrows(BizException.class, () -> support.list(10, 1, 51, null));
        assertThrows(BizException.class, () -> support.list(10, 1, 20, "ARBITRARY"));
        assertEquals(0, support.list(10, 1, 20, null).total());
        var first = support.open(10, "ok", "a");
        assertThrows(BizException.class, () -> support.messages(10, first.id(), 1L, 2L, 50));
        assertThrows(BizException.class, () -> support.messages(10, first.id(), null, null, 101));
        assertThrows(BizException.class, () -> support.get(10, "not-an-id"));
    }

    @Test void disabledOrChangedRolesAreCheckedForEveryOperation() {
        var first = support.open(10, "Question", "a");
        actors.get(10).setEnable(false);
        assertThrows(BizException.class, () -> support.list(10, 1, 20, null));
        assertThrows(BizException.class, () -> support.messages(10, first.id(), null, null, 20));
        assertThrows(BizException.class, () -> support.send(10, first.id(), "new", "b"));
        actors.get(10).setEnable(true);
        actors.get(1).setRole(UserRoleEnum.USER);
        assertThrows(BizException.class, () -> support.accept(1, first.id()));
        assertThrows(BizException.class, () -> support.get(1, first.id()));
        verify(users, atLeast(6)).requireLoginUser(anyInt());
    }

    @Test void concurrentOpenAndRetriesKeepOneActiveConversationAndOneMessagePerRequest() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int index = 0; index < 8; index++) tasks.add(() -> support.open(10, "Same retry", "same-open").id());
            var futures = executor.invokeAll(tasks);
            String first = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(10, TimeUnit.SECONDS));
            assertEquals(1, support.list(10, 1, 20, null).total());
            assertEquals(1, support.messages(10, first, null, null, 50).items().size());
            assertEquals(1, notifications.size());
        }
    }

    @Test void committedMessagesSurviveNotificationTransportFailure() {
        var isolated = new SupportService(jdbc, users, event -> { throw new IllegalStateException("transport unavailable"); },
                new DataSourceTransactionManager(jdbc.getDataSource()));
        var first = isolated.open(10, "Will remain saved", "request-a");
        assertEquals("Will remain saved", isolated.messages(10, first.id(), null, null, 50).items().getFirst().content());
    }

    @Test void ownerDeletionCleansConversationAndAdministratorRepliesWithoutTouchingOtherOwners() {
        var first = support.open(10, "Question", "request-a");
        support.send(1, first.id(), "Administrator reply", "reply-a");
        var other = support.open(11, "Unrelated question", "request-b");
        support.onUserDeleted(new UserDeleteEvent(actors.get(10)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE owner_user_id=10", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM support_message WHERE conversation_id=?", Integer.class, first.id()));
        assertEquals(1, support.messages(11, other.id(), null, null, 50).items().size());
    }

    private static User actor(int id, UserRoleEnum role, Integer tenant) {
        User actor = new User(); actor.setId(id); actor.setRole(role); actor.setEnable(true); actor.setTenantId(tenant);
        actor.setAccountId("SC-" + id); actor.setUsername("account-" + id); actor.setNickname("Account " + id); return actor;
    }
}
