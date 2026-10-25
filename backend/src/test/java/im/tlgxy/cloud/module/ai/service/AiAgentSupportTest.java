package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import im.tlgxy.cloud.module.ai.config.AiAgentProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic loopback model responses exercise suggestion handling without provider or business data. */
class AiAgentSupportTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectKnowledgeService knowledge = mock(ProjectKnowledgeService.class);
    private final AiAgentToolService tools = mock(AiAgentToolService.class);
    private final List<JsonNode> requests = new ArrayList<>();
    private AiAgentProperties properties;
    private AiAgentService agent;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiAgentProperties();
        properties.setApiKey("synthetic-test-only");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        properties.setMaxToolRounds(1);
        when(knowledge.resolveCurrentAudience()).thenReturn(KnowledgeAudience.USER);
        when(knowledge.search(anyString(), any())).thenReturn(List.of());
        when(knowledge.buildContext(anyList(), any())).thenReturn("合成测试知识");
        agent = new AiAgentService(properties, json, knowledge, tools);
    }

    @AfterEach
    void close() { agent.close(); server.stop(0); }

    @Test
    void explicitRequestWorksWhenModelIsNotConfiguredAndCreatesNoToolsOrEvidence() {
        properties.setApiKey(null);
        properties.setEnabled(false);
        AiChatResult result = agent.chat(question("人工服务"));
        assertNotNull(result.getSupportSuggestion());
        assertTrue(result.getAnswer().contains("确认前不会"));
        assertTrue(result.getTools().isEmpty());
        assertTrue(result.getKnowledge().isEmpty());
        assertTrue(requests.isEmpty());
        verifyNoInteractions(tools);
        verify(knowledge, never()).search(anyString(), any());
        JsonNode contract = json.valueToTree(result);
        assertTrue(contract.path("supportSuggestion").path("reason").isTextual());
    }

    @Test
    void unresolvedFeedbackProducesAStreamingDoneSuggestionWithoutProviderRequest() {
        properties.setApiKey(null);
        var request = question("你的回答没有帮助");
        var previous = new AiChatRequest.ChatMessage();
        previous.setRole("assistant");
        previous.setContent("请刷新页面后重试。");
        request.setHistory(repeatedHistory(previous));
        List<AiAgentService.StreamEvent> events = new ArrayList<>();
        agent.stream(UUID.randomUUID().toString(), 7, request, List.of(), events::add);
        assertEquals(List.of("accepted", "done"), events.stream().map(AiAgentService.StreamEvent::event).toList());
        assertNotNull(((AiChatResult) events.getLast().data()).getSupportSuggestion());
        assertTrue(requests.isEmpty());
        verifyNoInteractions(tools);
    }

    @Test
    void modelCanSuggestSupportAtLastToolRoundWithoutCreatingAConversationOrExtraModelCall() {
        serveSuggestion(false);
        var request = question("上传持续失败，需要管理员排查");
        request.setHistory(repeatedHistory(null));
        AiChatResult result = agent.chat(request);
        assertEquals("需要管理员核查存储连接。", result.getSupportSuggestion().reason());
        assertEquals(1, requests.size());
        assertEquals("suggest_human_support", result.getTools().getFirst().getName());
        assertTrue(result.getTools().getFirst().isSuccess());
        assertTrue(result.getAnswer().contains("确认前不会"));
        assertTrue(requests.getFirst().path("tools").toString().contains("suggest_human_support"));
        assertTrue(requests.getFirst().path("messages").get(0).path("content").asText().contains("绝不会联系管理员"));
        verifyNoInteractions(tools);
    }

    @Test
    void streamedModelSuggestionIsIncludedInFinalDoneEvent() {
        serveSuggestion(true);
        List<AiAgentService.StreamEvent> events = new ArrayList<>();
        var request = question("我反复上传失败");
        request.setHistory(repeatedHistory(null));
        agent.stream(UUID.randomUUID().toString(), 7, request, List.of(), events::add);
        assertEquals("done", events.getLast().event());
        AiChatResult result = (AiChatResult) events.getLast().data();
        assertEquals("需要管理员核查存储连接。", result.getSupportSuggestion().reason());
        assertEquals(1, requests.size());
        verifyNoInteractions(tools);
    }

    @Test
    void globalAdministratorDoesNotGetOfferedSupportAndInventedToolIsRejected() {
        when(knowledge.resolveCurrentAudience()).thenReturn(KnowledgeAudience.ADMIN);
        serveSuggestion(false);
        AiChatResult result = agent.chat(question("上传持续失败"));
        assertNull(result.getSupportSuggestion());
        assertEquals(2, requests.size());
        assertFalse(requests.getFirst().path("tools").toString().contains("suggest_human_support"));
        assertFalse(result.getTools().getFirst().isSuccess());
        verifyNoInteractions(tools);
    }

    @Test
    void explicitDeclineCannotBeOverriddenByAModelToolCall() {
        serveSuggestion(false);
        AiChatResult result = agent.chat(question("不要转人工，告诉我怎么解决上传失败"));
        assertNull(result.getSupportSuggestion());
        assertFalse(requests.getFirst().path("tools").toString().contains("suggest_human_support"));
        assertFalse(result.getTools().getFirst().isSuccess());
        verifyNoInteractions(tools);
    }

    private List<AiChatRequest.ChatMessage> repeatedHistory(AiChatRequest.ChatMessage first) {
        if (first == null) { first = new AiChatRequest.ChatMessage(); first.setRole("assistant"); first.setContent("先检查网络。"); }
        var feedback = new AiChatRequest.ChatMessage(); feedback.setRole("user"); feedback.setContent("没有解决");
        var second = new AiChatRequest.ChatMessage(); second.setRole("assistant"); second.setContent("再检查上传权限。");
        return List.of(first, feedback, second);
    }

    private AiChatRequest question(String message) {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(message);
        request.setPagePath("/");
        return request;
    }

    private void serveSuggestion(boolean streaming) {
        server.createContext("/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            var call = Map.of("id", "suggest-1", "type", "function", "function", Map.of(
                    "name", "suggest_human_support", "arguments", "{\"reason\":\"需要管理员核查存储连接。\"}"));
            String body;
            if (streaming) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                var streamCall = Map.of("index", 0, "id", "suggest-1", "type", "function", "function", call.get("function"));
                body = "data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0,
                        "delta", Map.of("tool_calls", List.of(streamCall)))))) + "\n\ndata: [DONE]\n\n";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                Object message = requests.size() == 1
                        ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(call))
                        : Map.of("role", "assistant", "content", "请检查管理设置。");
                body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", message))));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
}
