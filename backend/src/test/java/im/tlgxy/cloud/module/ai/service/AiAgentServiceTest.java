package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.config.AiAgentProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises actual HTTP model requests without a paid model or external network. */
class AiAgentServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private AiAgentToolService tools;
    private AiAgentProperties properties;
    private ProjectKnowledgeService knowledge;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiAgentProperties();
        properties.setApiKey("test-only-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        properties.setMaxToolRounds(1);
        tools = mock(AiAgentToolService.class);
        knowledge = mock(ProjectKnowledgeService.class);
        when(knowledge.resolveCurrentAudience()).thenReturn(KnowledgeAudience.USER);
        when(knowledge.buildContext(anyList(), eq(KnowledgeAudience.USER))).thenReturn("测试知识：文件上传需要确认");
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void callsToolAndSummarizesItsActualResult() throws Exception {
        serve(200);
        when(tools.execute(eq("list_storage_sources"), any())).thenReturn(
                new AiAgentToolService.ToolExecution(Map.of("count", 1), "已查询到 1 个可用存储源"));
        var result = service().chat(question());
        assertEquals("已完成查询", result.getAnswer());
        assertEquals(1, result.getTools().size());
        assertTrue(result.getTools().getFirst().isSuccess());
        assertEquals(15, requests.getFirst().path("tools").size());
        assertFalse(requests.getFirst().path("tools").toString().contains("suggest_human_support"));
        assertEquals(2048, requests.getFirst().path("max_tokens").asInt());
        assertFalse(requests.getFirst().has("thinking"), "Other providers must not receive DeepSeek extensions");
        assertFalse(requests.getLast().has("tools"), "After the round limit, model may only summarize");
        JsonNode messages = requests.getLast().path("messages");
        JsonNode toolResult = json.readTree(messages.get(messages.size() - 1).path("content").asText());
        assertEquals(1, toolResult.path("data").path("count").asInt());
        assertTrue(requests.getFirst().path("messages").get(0).path("content").asText().contains("测试知识"));
        verify(tools).execute(eq("list_storage_sources"), any());
    }

    @Test
    void toolFailureIsSanitizedAndReturnedAsFailedTrace() throws Exception {
        serve(200);
        when(tools.execute(anyString(), any())).thenThrow(new IllegalStateException("private-server-path"));
        var result = service().chat(question());
        assertFalse(result.getTools().getFirst().isSuccess());
        assertFalse(requests.getLast().path("messages").toString().contains("private-server-path"));
        assertEquals("已完成查询", result.getAnswer());
    }

    @Test
    void modelAuthenticationFailureDoesNotExposeCredentials() throws Exception {
        serve(401);
        BizException error = assertThrows(BizException.class, () -> service().chat(question()));
        assertTrue(error.getMessage().contains("认证失败"));
        assertFalse(error.getMessage().contains("test-only-key"));
        verifyNoInteractions(tools);
    }

    @Test
    void citationsComeFromExecutedRetrievalNotFromModelText() throws Exception {
        serve(200, "search_learning_materials");
        var citation = new im.tlgxy.cloud.module.learning.model.MaterialCitation("M4-S1", 4, 2,
                "notes.txt", "local", "/notes.txt", 1, "SQL transaction", 2);
        when(tools.execute(eq("search_learning_materials"), any())).thenReturn(new AiAgentToolService.ToolExecution(
                new im.tlgxy.cloud.module.learning.service.LearningLibraryService.SearchResult(List.of(citation), 0, "test"), "found"));
        var result = service().chat(question());
        assertEquals(List.of(citation), result.getCitations());
        assertTrue(requests.getLast().path("messages").toString().contains("M4-S1"));
        assertTrue(requests.getFirst().path("messages").get(0).path("content").asText().contains("不可信数据"));
    }

    private void serve(int status) { serve(status, "list_storage_sources"); }

    @Test
    void conciseModeStillHonorsExplicitRequestsForDetailedAnswers() {
        serve(200);
        when(tools.execute(anyString(), any())).thenReturn(new AiAgentToolService.ToolExecution(Map.of(), "ok"));
        var input = question();
        input.setMessage("请详细列出十条文件整理建议");
        service().chat(input);
        String prompt = requests.getFirst().path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("指定条数"));
        assertTrue(prompt.contains("不要说“这个我不做”"));
        assertTrue(requests.getFirst().path("messages").toString().contains("十条文件整理建议"));
    }

    @Test
    void deepSeekFlashExplicitlyDisablesThinkingByDefault() {
        properties.setModel("deepseek-flash");
        serve(200);
        when(tools.execute(anyString(), any())).thenReturn(new AiAgentToolService.ToolExecution(Map.of(), "ok"));
        service().chat(question());
        assertTrue(requests.stream().allMatch(request -> "disabled".equals(request.path("thinking").path("type").asText())));
    }

    @Test
    void deepSeekThinkingCanBeEnabledExplicitly() {
        properties.setModel("deepseek-flash");
        properties.setThinkingEnabled(true);
        properties.setMaxTokens(1024);
        serve(200);
        when(tools.execute(anyString(), any())).thenReturn(new AiAgentToolService.ToolExecution(Map.of(), "ok"));
        service().chat(question());
        assertTrue(requests.stream().allMatch(request -> "enabled".equals(request.path("thinking").path("type").asText())));
        assertEquals(1024, requests.getFirst().path("max_tokens").asInt());
    }

    @Test
    void planReturnsRealPendingCardWithoutAnotherModelRoundOrClaimingCompletion() {
        serve(200, "plan_file_organization");
        var plan = new im.tlgxy.cloud.module.ai.model.automation.Automation.View("plan-1", "分类整理", "PENDING", List.of(), List.of(), 1, 600001, "确认后执行");
        when(tools.execute(eq("plan_file_organization"), any())).thenReturn(new AiAgentToolService.ToolExecution(plan, "待确认"));
        var result = service().chat(question());
        assertEquals(1, requests.size(), "A plan should not incur another paid model round");
        assertEquals(List.of(plan), result.getPlans());
        assertTrue(result.getAnswer().contains("尚未修改"));
        assertFalse(requests.getFirst().path("tools").toString().contains("confirm_task"));
        assertFalse(requests.getFirst().path("tools").toString().contains("execute_task"));
    }

    @Test
    void responseIncludesOnlyActuallyRetrievedProjectKnowledge() {
        serve(200);
        var evidence = new im.tlgxy.cloud.module.ai.model.result.AiKnowledgeResult("user-example", "标题", "内容", "source", "user", 30);
        when(knowledge.search(anyString(), eq(KnowledgeAudience.USER))).thenReturn(List.of(evidence));
        when(tools.execute(anyString(), any())).thenReturn(new AiAgentToolService.ToolExecution(Map.of(), "ok"));
        assertEquals(List.of(evidence), service().chat(question()).getKnowledge());
    }

    @Test
    void emptyRetrievalCannotBePresentedAsAnEvidenceBasedAnswer() throws Exception {
        serve(200, "search_learning_materials");
        when(tools.execute(eq("search_learning_materials"), any())).thenReturn(new AiAgentToolService.ToolExecution(
                new im.tlgxy.cloud.module.learning.service.LearningLibraryService.SearchResult(List.of(), 1, "skipped"), "empty"));
        var result = service().chat(question());
        assertTrue(result.getAnswer().contains("未检索到足够依据"));
        assertTrue(result.getCitations().isEmpty());
    }

    private void serve(int status, String toolName) {
        server.createContext("/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            Object message = requests.size() == 1 ? Map.of(
                    "role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                            "id", "call-1", "type", "function", "function", Map.of("name", toolName, "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", "已完成查询");
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", message)))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    private AiAgentService service() { return new AiAgentService(properties, json, knowledge, tools); }

    private AiChatRequest question() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage("我有哪些云盘？");
        request.setPagePath("/workflow/");
        return request;
    }
}
