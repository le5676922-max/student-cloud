package io.studentcloud.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.assistant.AnswerFeedbackService;
import io.studentcloud.assistant.CourseKnowledgeAccess;
import io.studentcloud.assistant.FeedbackStatus;
import io.studentcloud.assistant.FeedbackView;
import io.studentcloud.assistant.KnowledgeScope;
import io.studentcloud.assistant.MemoryService;
import io.studentcloud.assistant.ReviewedKnowledgeService;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.ChatAnswerStore;
import io.studentcloud.intelligence.ai.ChatService;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** Real merged services and SQL, with only the external completion provider replaced by loopback. */
class MemoryFeedbackMergeIntegrationTest {
    private static final String MEMORY = "atomic preference OWNER_MEMORY_SENTINEL";
    private static final String QUESTION = "How does atomic preference work?";
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final Actor owner = new Actor(3, "SC_OWNER", "owner", null, Role.TENANT_ADMIN, 7L);
    private final Actor successor = new Actor(4, "SC_SUCCESSOR", "successor", null, Role.USER, 7L);
    private final Map<Long, Actor> enabled = new ConcurrentHashMap<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private JdbcTemplate jdbc;
    private MemoryService memories;
    private ChatAnswerStore answers;
    private AnswerFeedbackService feedback;
    private ReviewedKnowledgeService knowledge;
    private ChatService chat;
    private HttpServer server;

    @BeforeEach void setUp() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:memory_feedback_merge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                new ClassPathResource("db/independent-chat-schema.sql"),
                new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        var tx = new DataSourceTransactionManager(database);
        var clock = Clock.systemUTC();
        enabled.put(admin.id(), admin);
        enabled.put(owner.id(), owner);
        enabled.put(successor.id(), successor);
        var accounts = new AccountDirectory() {
            public Actor account(long id) {
                Actor found = enabled.get(id);
                if (found == null) throw new ApplicationFault("42000", 401, "Account unavailable");
                return found;
            }
            public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
        };
        var courses = CourseKnowledgeAccess.unavailable();
        memories = new MemoryService(jdbc, tx, clock, accounts);
        answers = new ChatAnswerStore(jdbc, clock);
        feedback = new AnswerFeedbackService(jdbc, tx, clock, accounts, courses, answers::requirePublishable);
        knowledge = new ReviewedKnowledgeService(jdbc, tx, clock, accounts, courses, answers::requirePublishable);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"Synthetic reviewed response.\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var model = new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "synthetic-key");
        chat = new ChatService(accounts, memories, knowledge, model, answers, null, null);
    }

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test void savedMemoryNeedsThisTurnsConsentBeforeItReachesTheProvider() {
        memories.create(owner, MEMORY);

        var withoutConsent = chat.answer(owner, request(false));
        assertFalse(requests.getFirst().toString().contains("OWNER_MEMORY_SENTINEL"));
        assertFalse(privateContext(withoutConsent.messageId()));

        var withConsent = chat.answer(owner, request(true));
        assertTrue(requests.get(1).toString().contains(MEMORY));
        assertTrue(privateContext(withConsent.messageId()));

        memories.enable(owner, false);
        var disabled = chat.answer(owner, request(true));
        assertFalse(requests.get(2).toString().contains("OWNER_MEMORY_SENTINEL"));
        assertFalse(privateContext(disabled.messageId()));
        assertEquals(1, memories.list(owner).size(), "Disabling recall must not erase the owner's record");
    }

    @Test void administratorHandoverAndOwnerMoveDoNotTransferMemoryOrItsEditVersion() throws Exception {
        var original = memories.create(owner, MEMORY);
        // Current identity is the account service boundary: a former workspace admin moves out,
        // while a different account becomes the old workspace's administrator.
        Actor movedOwner = new Actor(owner.id(), owner.accountId(), owner.username(), null, Role.USER, 8L);
        Actor nextAdministrator = new Actor(successor.id(), successor.accountId(), successor.username(), null, Role.TENANT_ADMIN, 7L);
        enabled.put(owner.id(), movedOwner);
        enabled.put(successor.id(), nextAdministrator);

        for (Actor administrator : List.of(nextAdministrator, admin)) {
            assertTrue(memories.list(administrator).isEmpty());
            assertEquals(404, assertThrows(ApplicationFault.class,
                    () -> memories.update(administrator, original.id(), 1, "stolen preference")).httpStatus());
            assertEquals(404, assertThrows(ApplicationFault.class,
                    () -> memories.delete(administrator, original.id(), 1)).httpStatus());
            var reply = chat.answer(administrator, request(true));
            assertFalse(requests.getLast().toString().contains("OWNER_MEMORY_SENTINEL"));
            assertFalse(new ObjectMapper().writeValueAsString(reply).contains("OWNER_MEMORY_SENTINEL"));
            assertFalse(privateContext(reply.messageId()));
        }

        memories.update(movedOwner, original.id(), 1, "atomic preference changed");
        memories.update(movedOwner, original.id(), 2, MEMORY);
        assertEquals(409, assertThrows(ApplicationFault.class,
                () -> memories.update(movedOwner, original.id(), 1, "stale old-window edit")).httpStatus());
        assertEquals(409, assertThrows(ApplicationFault.class,
                () -> memories.delete(movedOwner, original.id(), 1)).httpStatus());
        assertEquals(MEMORY, memories.list(movedOwner).getFirst().content());
        assertEquals(3, memories.list(movedOwner).getFirst().version());
        var ownReply = chat.answer(movedOwner, request(true));
        assertTrue(requests.getLast().toString().contains(MEMORY));
        assertTrue(privateContext(ownReply.messageId()));
    }

    @Test void humanCorrectionAndLegacyGlobalRowCannotEraseActualMemoryProvenance() {
        memories.create(owner, MEMORY);
        var reply = chat.answer(owner, request(true));
        answers.requireMatch(owner.id(), reply.messageId(), QUESTION, reply.answer(), null);
        var receipt = feedback.submit(owner, QUESTION, reply.answer(), reply.messageId());
        var reviewed = feedback.review(admin, receipt.id(), 1, "Public-looking sanitized correction", FeedbackStatus.RESOLVED);

        assertEquals(403, assertThrows(ApplicationFault.class,
                () -> knowledge.publish(admin, receipt.id(), reviewed.version(), "Sanitized", KnowledgeScope.GLOBAL, null, true)).httpStatus());
        // Defend old/imported rows too: a forged publication is not a substitute for provenance.
        jdbc.update("INSERT INTO sc_reviewed_knowledge(feedback_id,title,answer,scope,version,source_feedback_version,created_at) VALUES(?,?,?,'GLOBAL',1,?,?)",
                reviewed.id(), "Legacy row", reviewed.correctedAnswer(), reviewed.version(), reviewed.updatedAt());
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    @Test void resolvedPublicSourceMustBePublishedGloballyBeforeItCanBeExported() {
        var reviewed = publicReview();
        assertTrue(feedback.exportReviewed(admin).isEmpty());

        knowledge.publish(admin, reviewed.id(), reviewed.version(), "Public source", KnowledgeScope.GLOBAL, null, true);
        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer("Public question", "Public correction")), feedback.exportReviewed(admin));
    }

    @Test void sameTextRereviewInvalidatesTheOldGlobalPublicationUntilRepublished() {
        var reviewed = publicReview();
        knowledge.publish(admin, reviewed.id(), reviewed.version(), "Public source", KnowledgeScope.GLOBAL, null, true);
        var rereviewed = feedback.review(admin, reviewed.id(), reviewed.version(), "Public correction", "Changed review note", FeedbackStatus.RESOLVED);
        assertTrue(feedback.exportReviewed(admin).isEmpty());

        knowledge.publish(admin, rereviewed.id(), rereviewed.version(), "Public source", KnowledgeScope.GLOBAL, null, true);
        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer("Public question", "Public correction")), feedback.exportReviewed(admin));
    }

    @Test void narrowerPublicationCannotResurrectAnOlderGlobalGenerationAndWithdrawalStaysExcluded() {
        var reviewed = publicReview();
        knowledge.publish(admin, reviewed.id(), reviewed.version(), "Public source", KnowledgeScope.GLOBAL, null, true);
        var tenant = knowledge.publish(admin, reviewed.id(), reviewed.version(), "Workspace source", KnowledgeScope.TENANT, 7L, false);
        assertEquals(2, tenant.version());
        assertTrue(feedback.exportReviewed(admin).isEmpty());

        knowledge.withdraw(admin, reviewed.id(), tenant.version());
        assertTrue(feedback.exportReviewed(admin).isEmpty());
        var global = knowledge.publish(admin, reviewed.id(), reviewed.version(), "Public source", KnowledgeScope.GLOBAL, null, true);
        assertEquals(3, global.version());
        assertEquals(List.of(new AnswerFeedbackService.ReviewedAnswer("Public question", "Public correction")), feedback.exportReviewed(admin));
        knowledge.withdraw(admin, reviewed.id(), global.version());
        assertTrue(feedback.exportReviewed(admin).isEmpty());
    }

    private ChatService.Request request(boolean optIn) {
        return new ChatService.Request(QUESTION, List.of(), "/", "concise", null, optIn, null, null, null);
    }

    private boolean privateContext(String messageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT private_context FROM sc_chat_answer WHERE message_id=?", Boolean.class, messageId));
    }

    private FeedbackView publicReview() {
        String messageId = answers.save(owner.id(), owner.workspaceId(), null, "Public question", "Original public response", false);
        var receipt = feedback.submit(owner, "Public question", "Original public response", messageId);
        return feedback.review(admin, receipt.id(), 1, "Public correction", "PRIVATE REVIEW NOTE", FeedbackStatus.RESOLVED);
    }
}
