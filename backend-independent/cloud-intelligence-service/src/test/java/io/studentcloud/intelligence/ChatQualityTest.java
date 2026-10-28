package io.studentcloud.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic sources and a loopback completion server; no external provider or private files. */
class ChatQualityTest {
    private final Actor owner = new Actor(3, "SC_OWNER", "owner", null, Role.USER, 7L);
    private final Actor admin = new Actor(1, "SC_ADMIN", "admin", null, Role.ADMIN, null);
    private final AtomicReference<Actor> current = new AtomicReference<>(owner);
    private final AtomicReference<Runnable> duringCompletion = new AtomicReference<>(() -> { });
    private final AtomicReference<LearningKnowledgeClient.Search> material = new AtomicReference<>(new LearningKnowledgeClient.Search(List.of(), 0, false));
    private final AtomicBoolean materialAllowed = new AtomicBoolean(true);
    private final AtomicInteger materialCalls = new AtomicInteger();
    private final AtomicReference<String> materialQuery = new AtomicReference<>();
    private final AtomicReference<String> previewType = new AtomicReference<>();
    private final AtomicReference<Long> previewCourse = new AtomicReference<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private JdbcTemplate jdbc;
    private AccountDirectory accounts;
    private MemoryService memories;
    private AnswerFeedbackService feedback;
    private ReviewedKnowledgeService knowledge;
    private ChatAnswerStore answers;
    private LearningKnowledgeClient learning;
    private AgentPlanService plans;
    private ChatService chat;
    private HttpServer server;

    @BeforeEach void setup() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:chat_quality_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-assistant-schema.sql"),
                new ClassPathResource("db/independent-chat-schema.sql"), new ClassPathResource("db/independent-answer-provenance-schema.sql"),
                new ClassPathResource("db/independent-agent-schema.sql"), new ClassPathResource("db/independent-agent-plan-budget-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        var tx = new DataSourceTransactionManager(database);
        var clock = Clock.systemUTC();
        accounts = new AccountDirectory() {
            public Actor account(long id) { return id == admin.id() ? admin : current.get(); }
            public boolean workspaceEnabled(long id) { return id == 7 || id == 8; }
        };
        CourseKnowledgeAccess courses = (actor, id) -> {
            if (id != 12 || actor.id() != owner.id()) throw new ApplicationFault("40300", 403, "课程不可访问");
        };
        memories = new MemoryService(jdbc, tx, clock, accounts);
        feedback = new AnswerFeedbackService(jdbc, tx, clock, accounts, courses);
        knowledge = new ReviewedKnowledgeService(jdbc, tx, clock, accounts, courses);
        answers = new ChatAnswerStore(jdbc, clock);
        learning = new LearningKnowledgeClient(RestClient.builder()) {
            @Override public Search search(Actor actor, Long course, String query) {
                materialCalls.incrementAndGet(); materialQuery.set(query);
                if (!materialAllowed.get()) throw new ApplicationFault("40300", 403, "资料授权已撤销");
                return material.get();
            }
        };
        var previews = new AgentPlanService.PreviewPort() {
            public AgentContentClient.Preview preview(Actor actor, String key, List<AgentContentClient.Step> steps) { return result(steps); }
            public AgentContentClient.Preview previewBulk(Actor actor, String key, String path, String type, Long course, String tags) {
                previewType.set(type); previewCourse.set(course);
                return result(List.of(new AgentContentClient.Step(course == null ? "MOVE" : "INDEX_MATERIAL", "/notes.md", course == null ? "/docs/notes.md" : null, course, null)));
            }
            private AgentContentClient.Preview result(List<AgentContentClient.Step> steps) {
                return new AgentContentClient.Preview("synthetic-preview-" + UUID.randomUUID(), System.currentTimeMillis() + 600_000, steps);
            }
        };
        plans = new AgentPlanService(jdbc, tx, clock, accounts, null, null, previews);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
            duringCompletion.getAndSet(() -> { }).run();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"已核对当前依据。\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        chat = new ChatService(accounts, memories, knowledge, model("synthetic-key"), answers, learning, plans);
    }

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test void longNaturalQuestionUsesActualProjectHelpAndHumanSourceLabels() {
        String question = "如何".repeat(200) + "上传文件失败";
        var reply = chat.answer(owner, request(question, "concise", false, null));
        assertFalse(reply.knowledge().isEmpty());
        assertTrue(reply.citations().isEmpty());
        assertEquals(question, requests.getFirst().path("messages").get(requests.getFirst().path("messages").size() - 1).path("content").asText());
        for (Object entry : reply.knowledge()) {
            var view = (Map<?,?>) entry;
            assertEquals("项目使用说明", view.get("source"));
            assertFalse(view.toString().contains("backend-independent/"));
            assertNotNull(view.get("version"));
        }
    }

    @Test void conciseAndDetailedModesChangeTheResponseContractAndBudget() {
        chat.answer(owner, request("如何上传文件？", "concise", false, null));
        chat.answer(owner, request("如何上传文件？", "detailed", false, null));
        String brief = requests.get(0).path("messages").get(0).path("content").asText();
        String detailed = requests.get(1).path("messages").get(0).path("content").asText();
        assertTrue(brief.contains("2至4个短段落")); assertTrue(detailed.contains("关键理由、前提"));
        assertNotEquals(brief, detailed);
        assertTrue(brief.contains("代码围栏")); assertTrue(brief.contains("不编造引用"));
        assertEquals(700, requests.get(0).path("max_tokens").asInt());
        assertEquals(1600, requests.get(1).path("max_tokens").asInt());
    }

    @Test void reviewedKnowledgeBelongsToKnowledgePanelRatherThanMaterialCitations() {
        publish("上传文件", "审核后的上传说明", KnowledgeScope.GLOBAL);
        var reply = chat.answer(owner, request("如何上传文件？", "concise", false, null));
        assertTrue(reply.knowledge().stream().anyMatch(entry -> "GLOBAL".equals(((Map<?,?>) entry).get("scope"))));
        assertTrue(reply.citations().isEmpty());
        assertTrue(requests.getFirst().toString().contains("审核后的上传说明"));
    }

    @Test void noPrivateOptInExcludesWorkspaceAndMaterialTextBeforeProviderCall() {
        publish("atomic isolation", "private-workspace-sentinel", KnowledgeScope.TENANT);
        material.set(new LearningKnowledgeClient.Search(List.of(citation("private-material-sentinel", 1)), 0, false));
        var reply = chat.answer(owner, request("atomic isolation", "concise", false, 42L));
        assertEquals(0, materialCalls.get());
        assertFalse(requests.getFirst().toString().contains("private-workspace-sentinel"));
        assertFalse(requests.getFirst().toString().contains("private-material-sentinel"));
        assertTrue(reply.knowledge().isEmpty()); assertTrue(reply.citations().isEmpty());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT private_context FROM sc_chat_answer WHERE message_id=?", Boolean.class, reply.messageId()));
    }

    @Test void materialCitationsCarryCompleteMetadataAndCoverageIncludesContextBudget() {
        memories.create(owner, "用简洁语言回答。");
        material.set(new LearningKnowledgeClient.Search(List.of(citation("证".repeat(5800), 1), citation("据".repeat(500), 2)), 2, true));
        List<ChatService.Turn> history = new ArrayList<>();
        for (int index = 0; index < 8; index++) history.add(new ChatService.Turn(index % 2 == 0 ? "user" : "assistant", "synthetic turn " + index));
        String question = "如何".repeat(120) + "上传文件";
        var request = new ChatService.Request(question, history, "/learning", "concise", null, true, 42L, null, null);
        var reply = chat.answer(owner, request, "synthetic attachment");
        assertEquals(1, reply.citations().size());
        var hit = reply.citations().getFirst();
        assertEquals(42L, hit.get("courseId")); assertEquals(1L, hit.get("materialId"));
        assertEquals("local-drive", hit.get("storageKey")); assertEquals("/notes.md", hit.get("path"));
        assertEquals(3, reply.coverage().skipped()); assertTrue(reply.coverage().truncated());
        assertFalse(reply.coverage().notice().isBlank());
        assertEquals(question, materialQuery.get()); assertEquals(2, materialCalls.get());
        assertTrue(requests.getFirst().path("messages").size() <= 16);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT private_context FROM sc_chat_answer WHERE message_id=?", Boolean.class, reply.messageId()));
    }

    @Test void withdrawingKnowledgeDuringCompletionRejectsAnswerBeforePersistence() {
        var publication = publish("precise isolation", "Current instruction", KnowledgeScope.GLOBAL);
        duringCompletion.set(() -> knowledge.revoke(admin, publication.id(), publication.version()));
        assertEquals(409, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("precise isolation", "concise", false, null))).httpStatus());
        assertNoAnswerSaved();
    }

    @Test void workspaceChangeDuringCompletionRejectsAnswerBeforePersistence() {
        duringCompletion.set(() -> current.set(new Actor(owner.id(), owner.accountId(), owner.username(), null, Role.USER, 8L)));
        assertEquals(409, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("如何上传？", "concise", false, null))).httpStatus());
        assertNoAnswerSaved();
    }

    @Test void roleChangeDuringCompletionRejectsAnswerBeforePersistence() {
        duringCompletion.set(() -> current.set(new Actor(owner.id(), owner.accountId(), owner.username(), null, Role.TENANT_ADMIN, 7L)));
        assertEquals(409, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("如何上传？", "concise", false, null))).httpStatus());
        assertNoAnswerSaved();
    }

    @Test void changedMaterialSnapshotOrRevokedMaterialPermissionRejectsAnswer() {
        material.set(new LearningKnowledgeClient.Search(List.of(citation("synthetic source", 1)), 0, false));
        duringCompletion.set(() -> material.set(new LearningKnowledgeClient.Search(List.of(), 1, false)));
        assertEquals(409, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("课程资料", "concise", true, 42L))).httpStatus());
        material.set(new LearningKnowledgeClient.Search(List.of(citation("synthetic source", 1)), 0, false));
        duringCompletion.set(() -> materialAllowed.set(false));
        assertEquals(403, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("课程资料", "concise", true, 42L))).httpStatus());
        assertNoAnswerSaved();
    }

    @Test void disablingUsedMemoryDuringCompletionRejectsAnswer() {
        memories.create(owner, "Remember this synthetic preference.");
        duringCompletion.set(() -> memories.enable(owner, false));
        assertEquals(409, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("如何上传？", "concise", false, null))).httpStatus());
        assertNoAnswerSaved();
    }

    @Test void explicitCommandsClarifyMissingContextAndOnlyPersistPendingPreviews() {
        var clarification = chat.answer(owner, request("请整理目录中的文件", "concise", false, null));
        assertTrue(clarification.answer().contains("选择存储源和目标目录"));
        assertTrue(clarification.plans().isEmpty());
        var preview = chat.answer(owner, new ChatService.Request("请整理目录中的文件", List.of(), "/", "concise", null, false, null, "local-drive", "/notes"));
        assertEquals(1, preview.plans().size());
        assertEquals("PENDING", ((AgentPlanService.View) preview.plans().getFirst()).status());
        assertEquals("ORGANIZE_FILES", previewType.get());
        var missingCourse = chat.answer(owner, new ChatService.Request("请归档资料到个人课程", List.of(), "/", "concise", null, false, null, "local-drive", "/notes"));
        assertTrue(missingCourse.answer().contains("选择个人课程"));
        chat.answer(owner, new ChatService.Request("请归档资料到个人课程", List.of(), "/", "concise", null, false, 42L, "local-drive", "/notes"));
        assertEquals("IMPORT_LEARNING", previewType.get()); assertEquals(42L, previewCourse.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan WHERE state='PENDING' AND job_id IS NULL", Integer.class));
        assertTrue(requests.isEmpty());
    }

    @Test void helpQuestionUploadAndDeleteCommandsNeverStartAnAutomaticWrite() {
        var help = chat.answer(owner, new ChatService.Request("如何整理目录中的文件？", List.of(), "/", "concise", null, false, null, "local-drive", "/notes"));
        assertTrue(help.plans().isEmpty()); assertEquals(1, requests.size());
        var upload = chat.answer(owner, request("请上传文件", "concise", false, null));
        assertTrue(upload.answer().contains("添加文件"));
        var delete = chat.answer(owner, request("请删除目录里的文件", "concise", false, null));
        assertTrue(delete.answer().contains("回收站"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan", Integer.class));
        assertEquals(1, requests.size());
    }

    @Test void invalidHistoryAndStyleFailBeforeStartingAnIntent() {
        var request = new ChatService.Request("请整理目录中的文件", List.of(new ChatService.Turn(null, "invalid")), "/", "concise", null, false, null, "local-drive", "/notes");
        assertEquals(400, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request)).httpStatus());
        assertEquals(400, assertThrows(ApplicationFault.class, () -> chat.answer(owner, request("如何上传", "unknown", false, null))).httpStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_agent_plan", Integer.class));
        assertTrue(requests.isEmpty());
    }

    @Test void missingModelReturnsClearlyLabelledRealProjectHelpWithoutCallingProvider() {
        var offline = new ChatService(accounts, memories, knowledge, model(""), answers, learning, plans);
        var reply = offline.answer(owner, request("如何上传文件？", "concise", false, null));
        assertTrue(reply.answer().startsWith("**项目帮助**（AI模型尚未配置）"));
        assertTrue(reply.answer().contains("50MiB")); assertEquals(1, reply.knowledge().size());
        assertTrue(requests.isEmpty());
    }

    private OpenAiChatClient model(String key) { return new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", key); }
    private ChatService.Request request(String text, String style, boolean optIn, Long course) {
        return new ChatService.Request(text, List.of(), "/", style, null, optIn, course, null, null);
    }
    private LearningKnowledgeClient.Citation citation(String text, long id) {
        return new LearningKnowledgeClient.Citation("L" + id + "-1", "notes.md", 1, text, id, 42L, "local-drive", "/notes.md");
    }
    private KnowledgeView publish(String title, String corrected, KnowledgeScope scope) {
        var saved = feedback.submit(owner, title, "Unverified", UUID.randomUUID().toString());
        var reviewed = feedback.review(admin, saved.id(), 1, corrected, FeedbackStatus.RESOLVED);
        return knowledge.publish(admin, saved.id(), reviewed.version(), title, scope, scope == KnowledgeScope.TENANT ? 7L : null, scope == KnowledgeScope.GLOBAL);
    }
    private void assertNoAnswerSaved() { assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_chat_answer", Integer.class)); }
}
