package io.studentcloud.intelligence;

import com.sun.net.httpserver.HttpServer;
import io.studentcloud.account.*;
import io.studentcloud.assistant.*;
import io.studentcloud.intelligence.ai.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ChatMemoryPolicyTest {
    @Test void memoryNeverLeavesTheServiceWithoutPrivateContextConsent() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memories.create(fixture.user, "本人的复习偏好：private-memory-marker");
            fixture.ask("请解释复习偏好", false);
            assertFalse(fixture.request.get().contains("private-memory-marker"));
        }
    }

    @Test void optedInRecallUsesRelevantOlderMemoriesAndOmitsUnrelatedRecentOnes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memories.create(fixture.user, "复习提纲使用中文，先概念再例题 old-relevant-memory");
            for (int i = 0; i < 12; i++) fixture.memories.create(fixture.user, "无关的运动偏好 unrelated-marker-" + i);
            fixture.ask("请说明复习提纲的偏好", true);
            assertTrue(fixture.request.get().contains("old-relevant-memory"));
            assertFalse(fixture.request.get().contains("unrelated-marker"));
        }
    }

    @Test void disabledMemoryIsOmittedEvenWithConsent() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.memories.create(fixture.user, "复习提纲 disabled-private-marker");
            fixture.memories.enable(fixture.user, false);
            fixture.ask("复习提纲怎么写", true);
            assertFalse(fixture.request.get().contains("disabled-private-marker"));
        }
    }

    static final class Fixture implements AutoCloseable {
        final Actor user = new Actor(3, "member", "member", null, Role.USER, 7L);
        final AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) { return user; }
            public boolean workspaceEnabled(long id) { return id == 7; }
        };
        final JdbcTemplate jdbc;
        final MemoryService memories;
        final AtomicReference<String> request = new AtomicReference<>();
        final HttpServer server;
        final ChatService chat;
        Fixture() throws Exception {
            var database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:memory_policy_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                    new ClassPathResource("db/independent-chat-schema.sql"),
                    new ClassPathResource("db/independent-answer-provenance-schema.sql")).execute(database);
            jdbc = new JdbcTemplate(database);
            var tx = new DataSourceTransactionManager(database);
            var clock = Clock.systemUTC();
            memories = new MemoryService(jdbc, tx, clock, accounts);
            var store = new ChatAnswerStore(jdbc, clock);
            var knowledge = new ReviewedKnowledgeService(jdbc, tx, clock, accounts,
                    CourseKnowledgeAccess.unavailable(), store::requirePublishable);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"choices\":[{\"message\":{\"content\":\"这是合成测试回答。\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            chat = new ChatService(accounts, memories, knowledge, new OpenAiChatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture-key"), store, null, null);
        }
        void ask(String text, boolean consent) {
            chat.answer(user, new ChatService.Request(text, List.of(), "/", "concise", null, consent, null, null, null));
        }
        public void close() { server.stop(0); jdbc.execute("SHUTDOWN"); }
    }
}
