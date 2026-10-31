package io.studentcloud.intelligence;

import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.assistant.AnswerFeedbackService;
import io.studentcloud.assistant.CourseKnowledgeAccess;
import io.studentcloud.assistant.FeedbackStatus;
import io.studentcloud.assistant.KnowledgeScope;
import io.studentcloud.assistant.KnowledgeView;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

/** Real retrieval services and a localhost model capture; every marker is synthetic test data. */
class ChatMemberIsolationTest {
    private static final String QUERY = "isolationcheck";
    private static final Actor ADMIN = new Actor(1, "root", "root", null, Role.ADMIN, null);
    private static final Actor ALICE = member(3, 7);
    private static final Actor BOB = member(4, 7);
    private static final Actor EVE = member(5, 8);

    @Test void switchingBetweenSameRoleMembersNeverReusesAnotherAccountsMemoryOrAnswerReceipt() throws Exception {
        try (var f = new Fixture()) {
            f.memories.create(ALICE, QUERY + " ALICE_MEMORY_SECRET");
            f.memories.create(BOB, QUERY + " BOB_MEMORY_SECRET");
            f.memories.create(EVE, QUERY + " EVE_MEMORY_SECRET");
            for (Actor actor : List.of(ALICE, BOB, EVE, ALICE)) {
                var reply = f.ask(actor, null, true);
                String expected = actor.id() == ALICE.id() ? "ALICE_MEMORY_SECRET"
                        : actor.id() == BOB.id() ? "BOB_MEMORY_SECRET" : "EVE_MEMORY_SECRET";
                assertMarkers(f.lastRequest(), List.of(expected),
                        List.of("ALICE_MEMORY_SECRET", "BOB_MEMORY_SECRET", "EVE_MEMORY_SECRET").stream()
                                .filter(marker -> !marker.equals(expected)).toList());
                f.store.requireMatch(actor.id(), reply.messageId(), QUERY, reply.answer(), null);
                Actor peer = actor.id() == ALICE.id() ? BOB : ALICE;
                assertEquals(409, assertThrows(ApplicationFault.class,
                        () -> f.store.requireMatch(peer.id(), reply.messageId(), QUERY, reply.answer(), null)).httpStatus());
                assertTrue(f.jdbc.queryForObject("SELECT private_context FROM sc_chat_answer WHERE message_id=?",
                        Boolean.class, reply.messageId()));
            }
        }
    }

    @Test void memoryIdsSettingsRecallAndMutationsBelongToAnAccountEvenWithinOneWorkspace() throws Exception {
        try (var f = new Fixture()) {
            var aliceMemory = f.memories.create(ALICE, QUERY + " ALICE_MEMORY_SECRET");
            var bobMemory = f.memories.create(BOB, QUERY + " BOB_MEMORY_SECRET");
            assertEquals(List.of(aliceMemory), f.memories.recall(ALICE, QUERY, 10));
            assertEquals(List.of(bobMemory), f.memories.recall(BOB, QUERY, 10));
            assertEquals(404, assertThrows(ApplicationFault.class,
                    () -> f.memories.update(BOB, aliceMemory.id(), 1, "stolen")).httpStatus());
            assertEquals(404, assertThrows(ApplicationFault.class,
                    () -> f.memories.delete(BOB, aliceMemory.id(), 1)).httpStatus());
            f.memories.enable(BOB, false);
            assertTrue(f.memories.settings(ALICE).enabled());
            assertTrue(f.memories.recall(BOB, QUERY, 10).isEmpty());
            f.memories.clear(BOB);
            assertEquals(List.of(aliceMemory), f.memories.list(ALICE));
            assertTrue(f.memories.list(BOB).isEmpty());
            assertEquals(1, f.memories.settings(ALICE).count());
        }
    }

    @Test void sharedKnowledgeReachesOnlyTheCurrentWorkspaceOrExplicitCourseMembersBeforeTheModel() throws Exception {
        try (var f = new Fixture()) {
            f.publish(ALICE, KnowledgeScope.GLOBAL, null, "GLOBAL_SHARED");
            f.publish(ALICE, KnowledgeScope.TENANT, null, "WORKSPACE_SEVEN_SHARED");
            f.publish(EVE, KnowledgeScope.TENANT, null, "WORKSPACE_EIGHT_SHARED");
            f.readers.add(ALICE.id()); f.readers.add(BOB.id());
            f.publish(ALICE, KnowledgeScope.COURSE, 12L, "COURSE_TWELVE_SHARED");

            for (Actor actor : List.of(ALICE, BOB)) {
                var reply = f.ask(actor, 12L, true);
                assertMarkers(f.lastRequest(), List.of("GLOBAL_SHARED", "WORKSPACE_SEVEN_SHARED", "COURSE_TWELVE_SHARED"),
                        List.of("WORKSPACE_EIGHT_SHARED"));
                assertEquals(3, reply.knowledge().size());
            }
            f.ask(EVE, null, true);
            assertMarkers(f.lastRequest(), List.of("GLOBAL_SHARED", "WORKSPACE_EIGHT_SHARED"),
                    List.of("WORKSPACE_SEVEN_SHARED", "COURSE_TWELVE_SHARED"));
            f.ask(BOB, null, true);
            assertMarkers(f.lastRequest(), List.of("GLOBAL_SHARED", "WORKSPACE_SEVEN_SHARED"),
                    List.of("WORKSPACE_EIGHT_SHARED", "COURSE_TWELVE_SHARED"));
            f.ask(BOB, 12L, false);
            assertMarkers(f.lastRequest(), List.of("GLOBAL_SHARED"),
                    List.of("WORKSPACE_SEVEN_SHARED", "WORKSPACE_EIGHT_SHARED", "COURSE_TWELVE_SHARED"));
            int calls = f.requests.size();
            assertEquals(403, assertThrows(ApplicationFault.class, () -> f.ask(EVE, 12L, true)).httpStatus());
            assertEquals(calls, f.requests.size(), "A nonmember must be denied before any model request");
        }
    }

    @Test void aStaleSuppliedWorkspaceCannotSelectTheOldTenantIndex() throws Exception {
        try (var f = new Fixture()) {
            f.publish(ALICE, KnowledgeScope.TENANT, null, "WORKSPACE_SEVEN_SHARED");
            f.publish(EVE, KnowledgeScope.TENANT, null, "WORKSPACE_EIGHT_SHARED");
            f.memories.create(ALICE, QUERY + " ALICE_MEMORY_SECRET");
            f.memories.create(EVE, QUERY + " EVE_MEMORY_SECRET");
            f.enabled.put(ALICE.id(), member(ALICE.id(), 8));
            f.ask(ALICE, null, true);
            assertMarkers(f.lastRequest(), List.of("WORKSPACE_EIGHT_SHARED", "ALICE_MEMORY_SECRET"),
                    List.of("WORKSPACE_SEVEN_SHARED", "EVE_MEMORY_SECRET"));
        }
    }

    @Test void aRevokedCourseMemberNeverSendsCourseKnowledgeToTheModel() throws Exception {
        try (var f = new Fixture()) {
            f.readers.add(ALICE.id());
            f.publish(ALICE, KnowledgeScope.COURSE, 12L, "COURSE_TWELVE_SHARED");
            f.readers.remove(ALICE.id());
            long before = f.answerCount();
            assertEquals(403, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, 12L, true)).httpStatus());
            assertTrue(f.requests.isEmpty());
            assertEquals(before, f.answerCount());
        }
    }

    @Test void courseMembershipRevokedDuringCompletionRejectsTheAnswerAndItsReceipt() throws Exception {
        try (var f = new Fixture()) {
            f.readers.add(ALICE.id());
            f.publish(ALICE, KnowledgeScope.COURSE, 12L, "COURSE_TWELVE_SHARED");
            long before = f.answerCount();
            f.duringModel.set(() -> f.readers.remove(ALICE.id()));
            assertEquals(403, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, 12L, true)).httpStatus());
            assertTrue(f.lastRequest().contains("COURSE_TWELVE_SHARED"));
            assertEquals(before, f.answerCount());
            assertEquals(403, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, 12L, true)).httpStatus());
            assertEquals(1, f.requests.size(), "Revocation must block the next request before the model");
        }
    }

    @Test void workspaceChangeDuringCompletionRejectsOldContextAndTheNextRequestUsesFreshMembership() throws Exception {
        try (var f = new Fixture()) {
            f.publish(ALICE, KnowledgeScope.TENANT, null, "WORKSPACE_SEVEN_SHARED");
            f.publish(EVE, KnowledgeScope.TENANT, null, "WORKSPACE_EIGHT_SHARED");
            long before = f.answerCount();
            f.duringModel.set(() -> f.enabled.put(ALICE.id(), member(ALICE.id(), 8)));
            assertEquals(409, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, null, true)).httpStatus());
            assertTrue(f.lastRequest().contains("WORKSPACE_SEVEN_SHARED"));
            assertEquals(before, f.answerCount());
            f.ask(ALICE, null, true);
            assertMarkers(f.lastRequest(), List.of("WORKSPACE_EIGHT_SHARED"), List.of("WORKSPACE_SEVEN_SHARED"));
        }
    }

    @Test void disablingMemoryDuringCompletionRejectsTheAnswerAndSubsequentRecallOmitsIt() throws Exception {
        try (var f = new Fixture()) {
            f.memories.create(ALICE, QUERY + " ALICE_MEMORY_SECRET");
            long before = f.answerCount();
            f.duringModel.set(() -> f.memories.enable(ALICE, false));
            assertEquals(409, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, null, true)).httpStatus());
            assertTrue(f.lastRequest().contains("ALICE_MEMORY_SECRET"));
            assertEquals(before, f.answerCount());
            f.ask(ALICE, null, true);
            assertFalse(f.lastRequest().contains("ALICE_MEMORY_SECRET"));
        }
    }

    @Test void withdrawingReviewedKnowledgeDuringCompletionRejectsTheAnswerAndNextRetrievalOmitsIt() throws Exception {
        try (var f = new Fixture()) {
            var published = f.publish(ALICE, KnowledgeScope.TENANT, null, "WORKSPACE_SEVEN_SHARED");
            long before = f.answerCount();
            f.duringModel.set(() -> f.knowledge.revoke(ADMIN, published.id(), published.version()));
            assertEquals(409, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, null, true)).httpStatus());
            assertTrue(f.lastRequest().contains("WORKSPACE_SEVEN_SHARED"));
            assertEquals(before, f.answerCount());
            f.ask(ALICE, null, true);
            assertFalse(f.lastRequest().contains("WORKSPACE_SEVEN_SHARED"));
        }
    }

    @Test void disablingAnAccountDuringCompletionRejectsTheAnswerAndBlocksFurtherModelCalls() throws Exception {
        try (var f = new Fixture()) {
            f.memories.create(ALICE, QUERY + " ALICE_MEMORY_SECRET");
            f.duringModel.set(() -> f.enabled.remove(ALICE.id()));
            assertEquals(401, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, null, true)).httpStatus());
            assertEquals(0, f.answerCount());
            assertEquals(401, assertThrows(ApplicationFault.class, () -> f.ask(ALICE, null, true)).httpStatus());
            assertEquals(1, f.requests.size());
        }
    }

    private static Actor member(long id, long workspace) {
        return new Actor(id, "account-" + id, "member-" + id, null, Role.USER, workspace);
    }

    private static void assertMarkers(String body, List<String> present, List<String> absent) {
        for (String marker : present) assertTrue(body.contains(marker), "Missing permitted marker: " + marker);
        for (String marker : absent) assertFalse(body.contains(marker), "Leaked forbidden marker: " + marker);
    }

    private static final class Fixture implements AutoCloseable {
        final Map<Long, Actor> enabled = new ConcurrentHashMap<>(Map.of(1L, ADMIN, 3L, ALICE, 4L, BOB, 5L, EVE));
        final Set<Long> readers = ConcurrentHashMap.newKeySet();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Runnable> duringModel = new AtomicReference<>();
        final JdbcTemplate jdbc;
        final MemoryService memories;
        final ChatAnswerStore store;
        final AnswerFeedbackService feedback;
        final ReviewedKnowledgeService knowledge;
        final HttpServer server;
        final ChatService chat;

        Fixture() throws Exception {
            var database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:chat_member_isolation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                    new ClassPathResource("db/independent-chat-schema.sql"),
                    new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(database);
            jdbc = new JdbcTemplate(database);
            AccountDirectory accounts = new AccountDirectory() {
                public Actor account(long id) {
                    Actor actor = enabled.get(id);
                    if (actor == null) throw new ApplicationFault("42000", 401, "Synthetic account disabled");
                    return actor;
                }
                public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
            };
            CourseKnowledgeAccess courses = (actor, course) -> {
                if (course != 12 || !readers.contains(actor.id()))
                    throw new ApplicationFault("40300", 403, "Synthetic course membership required");
            };
            var tx = new DataSourceTransactionManager(database);
            var clock = Clock.systemUTC();
            memories = new MemoryService(jdbc, tx, clock, accounts);
            store = new ChatAnswerStore(jdbc, clock);
            feedback = new AnswerFeedbackService(jdbc, tx, clock, accounts, courses);
            knowledge = new ReviewedKnowledgeService(jdbc, tx, clock, accounts, courses, store::requirePublishable);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Runnable change = duringModel.getAndSet(null);
                if (change != null) change.run();
                byte[] response = "{\"choices\":[{\"message\":{\"content\":\"Synthetic completed answer.\"},\"finish_reason\":\"stop\"}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var model = new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "synthetic-local-key");
            chat = new ChatService(accounts, memories, knowledge, model, store, null, null);
        }

        KnowledgeView publish(Actor author, KnowledgeScope scope, Long course, String marker) {
            String seed = store.save(author.id(), author.workspaceId(), course, QUERY, "Synthetic seed answer.");
            var receipt = feedback.submit(author, QUERY, "Synthetic seed answer.", seed, course);
            var reviewed = feedback.review(ADMIN, receipt.id(), 1, QUERY + " " + marker, FeedbackStatus.RESOLVED);
            return knowledge.publish(ADMIN, receipt.id(), reviewed.version(), QUERY + " " + marker, scope,
                    scope == KnowledgeScope.TENANT ? author.workspaceId() : null, scope == KnowledgeScope.GLOBAL);
        }

        ChatService.Reply ask(Actor actor, Long course, boolean consent) {
            return chat.answer(actor, new ChatService.Request(QUERY, List.of(), "/", "concise", course, consent, null, null, null));
        }
        String lastRequest() { return requests.getLast(); }
        long answerCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM sc_chat_answer", Long.class); }
        public void close() { server.stop(0); jdbc.execute("SHUTDOWN"); }
    }
}
