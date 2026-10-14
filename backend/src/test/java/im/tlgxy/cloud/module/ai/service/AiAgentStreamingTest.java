package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.config.AiAgentProperties;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentStreamingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<AiAgentService.StreamEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseServer = new CountDownLatch(1);
    private HttpServer server;
    private AiAgentProperties properties;
    private AiAgentToolService tools;
    private AiAgentService service;
    private ProjectKnowledgeService knowledge;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiAgentProperties();
        properties.setApiKey("local-fake-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setMaxToolRounds(1);
        tools = mock(AiAgentToolService.class);
        knowledge = mock(ProjectKnowledgeService.class);
        when(knowledge.resolveCurrentAudience()).thenReturn(KnowledgeAudience.USER);
        when(knowledge.buildContext(anyList(), any())).thenReturn("test");
    }

    @AfterEach
    void tearDown() {
        releaseServer.countDown();
        if (service != null) service.close();
        server.stop(0);
    }

    @Test
    void deliversRealTokensBeforeModelFinishesAndFinalRetainsActualKnowledge() throws Exception {
        CountDownLatch firstToken = new CountDownLatch(1);
        var evidence = new im.tlgxy.cloud.module.ai.model.result.AiKnowledgeResult("user-source", "标题", "内容", "source", "user", 30);
        when(knowledge.search(anyString(), any())).thenReturn(List.of(evidence));
        server.createContext("/chat/completions", exchange -> {
            start(exchange);
            chunk(exchange, Map.of("content", "第一段中文"));
            try { assertTrue(firstToken.await(2, TimeUnit.SECONDS), "Token must arrive before provider completion"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            chunk(exchange, Map.of("content", "，第二段"));
            finish(exchange);
        });
        startService();
        service.stream(id(), 7, question(), List.of(), event -> {
            events.add(event);
            if (event.event().equals("token")) firstToken.countDown();
        });
        assertTrue(requests.getFirst().path("stream").asBoolean());
        assertEquals(2, events.stream().filter(e -> e.event().equals("token")).count());
        assertEquals("第一段中文，第二段", result().getAnswer());
        assertEquals(List.of(evidence), result().getKnowledge());
        assertEquals("done", events.getLast().event());
    }

    @Test
    void buffersFragmentedToolArgumentsUntilCompleteThenStreamsAuthoritativeResult() {
        AtomicBoolean toolComplete = new AtomicBoolean();
        when(tools.execute(eq("list_storage_sources"), any())).thenAnswer(invocation -> {
            assertTrue(toolComplete.get());
            assertEquals("local", ((JsonNode) invocation.getArgument(1)).path("storageKey").asText());
            return new AiAgentToolService.ToolExecution(Map.of("count", 1), "已找到 1 个云盘");
        });
        server.createContext("/chat/completions", exchange -> {
            start(exchange);
            if (requests.size() == 1) {
                chunk(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-1", "function",
                        Map.of("name", "list_storage_sources", "arguments", "{\"storage")))));
                chunk(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "function", Map.of("arguments", "Key\":\"local\"}")))));
                toolComplete.set(true);
            } else chunk(exchange, Map.of("content", "已查询到实际结果"));
            finish(exchange);
        });
        startService();
        service.stream(id(), 7, question(), List.of(), events::add);
        assertEquals(2, requests.size());
        assertFalse(requests.getLast().has("tools"));
        assertEquals("已查询到实际结果", result().getAnswer());
        assertEquals(1, events.stream().filter(e -> e.event().equals("tool")).count());
        assertEquals(1, result().getTools().size());
        verify(tools, times(1)).execute(anyString(), any());
    }

    @Test
    void incompleteStreamNeverExecutesPartialToolArgumentsOrProducesDone() {
        server.createContext("/chat/completions", exchange -> {
            start(exchange);
            chunk(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-1", "function",
                    Map.of("name", "plan_file_organization", "arguments", "{\"storage")))));
            exchange.close();
        });
        startService();
        BizException error = assertThrows(BizException.class, () -> service.stream(id(), 7, question(), List.of(), events::add));
        assertTrue(error.getMessage().contains("提前中断"));
        verifyNoInteractions(tools);
        assertFalse(events.stream().anyMatch(e -> e.event().equals("done")));
    }

    @Test
    void taskPlanRemainsPendingAndFinalOverridesAnUntrustedCompletionClaim() {
        var plan = new Automation.View("plan-1", "整理", "PENDING", List.of(), List.of(), 1, 600001, "确认后执行");
        when(tools.execute(eq("plan_file_organization"), any())).thenReturn(new AiAgentToolService.ToolExecution(plan, "待确认"));
        server.createContext("/chat/completions", exchange -> {
            start(exchange);
            chunk(exchange, Map.of("content", "已完成全部移动", "tool_calls", List.of(Map.of("index", 0, "id", "call-1", "function",
                    Map.of("name", "plan_file_organization", "arguments", "{}")))));
            finish(exchange);
        });
        startService();
        service.stream(id(), 7, question(), List.of(), events::add);
        assertEquals(1, requests.size());
        assertTrue(result().getAnswer().contains("尚未修改"));
        assertEquals(List.of(plan), result().getPlans());
        assertFalse(requests.getFirst().path("tools").toString().contains("confirm_task"));
    }

    @Test
    void ownerCancellationClosesStalledUpstreamAndDoesNotAllowAnotherUserToCancel() throws Exception {
        CountDownLatch connected = stallServer();
        startService();
        String requestId = id();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> assertThrows(BizException.class,
                    () -> service.stream(requestId, 7, question(), List.of(), events::add)));
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            service.cancel(requestId, 8);
            assertFalse(future.isDone());
            service.cancel(requestId, 7);
            assertTrue(future.get(2, TimeUnit.SECONDS).getMessage().contains("停止"));
        }
        assertFalse(events.stream().anyMatch(e -> e.event().equals("done")));
    }

    @Test
    void wholeChatDeadlineClosesAResponseThatHasHeadersButNeverCompletes() throws Exception {
        properties.setChatTimeoutSeconds(1); // short fixture; deployed configuration validates a minimum of 10 s
        stallServer();
        startService();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            BizException error = assertThrows(BizException.class, () -> service.stream(id(), 7, question(), List.of(), events::add));
            assertTrue(error.getMessage().contains("超时"));
        });
    }

    @Test
    void admissionHasPerUserAndGlobalBoundsAndReleasesCompletedSlots() {
        try (var sessions = new AiChatSessions()) {
            try (var first = sessions.open("a", 7, 3, Duration.ofSeconds(10));
                 var second = sessions.open("b", 7, 3, Duration.ofSeconds(10));
                 var third = sessions.open("c", 8, 3, Duration.ofSeconds(10))) {
                assertThrows(BizException.class, () -> sessions.open("d", 7, 3, Duration.ofSeconds(10)));
                assertThrows(BizException.class, () -> sessions.open("e", 9, 3, Duration.ofSeconds(10)));
                assertThrows(BizException.class, () -> sessions.open("a", 8, 3, Duration.ofSeconds(10)));
            }
            try (var reopened = sessions.open("d", 7, 3, Duration.ofSeconds(10))) { reopened.check(); }
        }
    }

    private CountDownLatch stallServer() {
        CountDownLatch connected = new CountDownLatch(1);
        server.createContext("/chat/completions", exchange -> {
            start(exchange);
            exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            connected.countDown();
            try { releaseServer.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        return connected;
    }

    private void startService() {
        server.start();
        service = new AiAgentService(properties, json, knowledge, tools);
    }

    private void start(HttpExchange exchange) throws IOException {
        requests.add(json.readTree(exchange.getRequestBody()));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private void chunk(HttpExchange exchange, Object delta) throws IOException {
        String data = "data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0, "delta", delta)))) + "\n\n";
        exchange.getResponseBody().write(data.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private void finish(HttpExchange exchange) throws IOException {
        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private AiChatResult result() { return (AiChatResult) events.getLast().data(); }
    private String id() { return UUID.randomUUID().toString(); }
    private AiChatRequest question() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage("列出我的云盘");
        return request;
    }
}
