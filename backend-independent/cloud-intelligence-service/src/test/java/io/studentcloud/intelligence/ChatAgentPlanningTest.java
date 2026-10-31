package io.studentcloud.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.*;
import io.studentcloud.assistant.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.*;
import io.studentcloud.intelligence.agent.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ChatAgentPlanningTest {
    @Test void concreteGoalCreatesOnlyContentValidatedPendingPreview() throws Exception {
        try (var f = new Fixture()) {
            var reply = f.ask(true);
            assertEquals(1, f.calls.get());
            assertEquals(1, f.previews.get());
            assertEquals(1, reply.plans().size());
            assertEquals("PENDING", ((AgentPlanService.View) reply.plans().getFirst()).status());
            assertTrue(reply.answer().contains("未执行"));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE job_id IS NOT NULL", Integer.class));
            assertEquals(Boolean.TRUE, f.jdbc.queryForObject("SELECT private_context FROM sc_chat_answer WHERE message_id=?", Boolean.class, reply.messageId()));
        }
    }
    @Test void selectedPrivatePathRequiresConsentBeforeModelPlanning() throws Exception {
        try (var f = new Fixture()) {
            var reply = f.ask(false);
            assertEquals(0, f.calls.get());
            assertEquals(0, f.previews.get());
            assertTrue(reply.plans().isEmpty());
            assertTrue(reply.answer().contains("私有"));
        }
    }
    @Test void accountChangeDuringModelCallRejectsPlanBeforeContentPreview() throws Exception {
        try (var f = new Fixture()) {
            f.during.set(() -> f.current.set(new Actor(3, "SC_TEST", "member", null, Role.USER, 8L)));
            assertEquals(409, assertThrows(ApplicationFault.class, () -> f.ask(true)).httpStatus());
            assertEquals(0, f.previews.get());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan", Integer.class));
        }
    }
    @Test void oneNamedCourseFileCannotExpandToBulkImport() throws Exception {
        try (var f = new Fixture()) {
            f.modelReply.set("{\"kind\":\"PLAN\",\"steps\":[{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"courseId\":7}]}");
            var reply = f.chat.answer(f.user, new ChatService.Request("导入a.pdf到本人学习课程", List.of(), "/learning", "concise", null, true, 7L, "local", "/selected"));
            assertEquals(1, f.calls.get());
            var plan = (AgentPlanService.View) reply.plans().getFirst();
            assertEquals(1, plan.steps().size());
            assertEquals("/selected/a.pdf", plan.steps().getFirst().sourcePath());
        }
    }
    @Test void customOrganizeGoalCannotBeReplacedWithGenericDirectoryClassification() throws Exception {
        try (var f = new Fixture()) {
            var reply = f.chat.answer(f.user, new ChatService.Request("整理当前目录的文件，先把a.txt复制到archive/a.txt", List.of(), "/", "concise", null, true, null, "local", "/selected"));
            assertEquals(1, f.calls.get());
            assertEquals("COPY_FILE", ((AgentPlanService.View) reply.plans().getFirst()).steps().getFirst().action());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Actor user = new Actor(3, "SC_TEST", "member", null, Role.USER, 7L);
        final AtomicReference<Actor> current = new AtomicReference<>(user);
        final AtomicReference<Runnable> during = new AtomicReference<>(() -> {});
        final AtomicInteger calls = new AtomicInteger(), previews = new AtomicInteger();
        final AtomicReference<String> modelReply = new AtomicReference<>("{\"kind\":\"PLAN\",\"steps\":[{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/archive/a.txt\"}]}");
        final JdbcTemplate jdbc;
        final HttpServer server;
        final ChatService chat;
        Fixture() throws Exception {
            var db = new JdbcDataSource(); db.setURL("jdbc:h2:mem:chat_agent_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                    new ClassPathResource("db/independent-chat-schema.sql"), new ClassPathResource("db/independent-answer-provenance-schema.sql"),
                    new ClassPathResource("db/independent-agent-schema.sql"), new ClassPathResource("db/independent-agent-plan-budget-schema.sql")).execute(db);
            jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db); var clock = Clock.systemUTC();
            AccountDirectory accounts = new AccountDirectory() {
                public Actor account(long id) { return current.get(); }
                public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
            };
            var store = new ChatAnswerStore(jdbc, clock);
            var knowledge = new ReviewedKnowledgeService(jdbc, tx, clock, accounts, CourseKnowledgeAccess.unavailable(), store::requirePublishable);
            var plans = new AgentPlanService(jdbc, tx, clock, accounts, null, null, new AgentPlanService.PreviewPort() {
                public AgentContentClient.Preview preview(Actor actor, String key, List<AgentContentClient.Step> steps) {
                    previews.incrementAndGet(); assertEquals("local", key); assertEquals(user, actor);
                    return new AgentContentClient.Preview("synthetic-content-checked", clock.millis() + 600_000, steps);
                }
                public AgentContentClient.Preview previewBulk(Actor actor, String key, String path, String type, Long course, String tags) { throw new AssertionError("not a bulk operation"); }
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes(); calls.incrementAndGet(); during.get().run();
                String plan = modelReply.get();
                byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", plan), "finish_reason", "stop"))));
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            var model = new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture-key");
            chat = new ChatService(accounts, new MemoryService(jdbc, tx, clock, accounts), knowledge, model, store,
                    null, plans, new ProjectKnowledgeService(), new AgentModelPlanner(model));
        }
        ChatService.Reply ask(boolean optIn) {
            return chat.answer(user, new ChatService.Request("请把a.txt复制到archive/a.txt", List.of(), "/", "concise", null, optIn, null, "local", "/selected"));
        }
        public void close() { server.stop(0); jdbc.execute("SHUTDOWN"); }
    }
}
