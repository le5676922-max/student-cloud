package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.AiAgentProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in paid provider smoke test. Only synthetic data is sent; no database or file access. */
@EnabledIfEnvironmentVariable(named = "STUDENT_CLOUD_LIVE_AI_TEST", matches = "true")
@Timeout(65)
class AiDeepSeekLiveTest {
    @Test
    void actualProviderStreamsAnAnswerThroughTheApplicationService() {
        run("请只回复：连接成功。不要调用工具。", false);
    }

    @Test
    void actualProviderCallsAReadOnlyToolAndSummarizesItsSyntheticResult() {
        run("请调用 get_current_user 查询当前账号，并在回复中原样包含返回的 username，不要猜测。", true);
    }

    @Test
    void conciseModeDoesNotRefuseAnExplicitTenItemList() {
        AiChatResult result = run("请用 1. 到 10. 编号，给出十条云盘文件整理建议，每条一行、不超过十五个汉字。不要调用工具。", false);
        assertFalse(result.getAnswer().contains("这个我不做"));
        var numbered = java.util.regex.Pattern.compile("(?m)^\\s*(?:[1-9]|10)\\.\\s").matcher(result.getAnswer());
        assertEquals(10, numbered.results().count(), result.getAnswer());
    }

    private AiChatResult run(String prompt, boolean expectTool) {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertNotNull(key, "Set DEEPSEEK_API_KEY before opting into live tests");
        assertFalse(key.isBlank());
        AiAgentProperties properties = new AiAgentProperties();
        properties.setApiKey(key);
        properties.setMaxTokens(768);
        properties.setMaxToolRounds(1);
        properties.setChatTimeoutSeconds(60);
        ProjectKnowledgeService knowledge = mock(ProjectKnowledgeService.class);
        when(knowledge.resolveCurrentAudience()).thenReturn(KnowledgeAudience.USER);
        when(knowledge.buildContext(anyList(), any())).thenReturn("连通性测试，无私人资料。");
        AiAgentToolService tools = mock(AiAgentToolService.class);
        when(tools.execute(eq("get_current_user"), any())).thenReturn(new AiAgentToolService.ToolExecution(
                Map.of("username", "ai-smoke-user", "role", "普通用户"), "查询测试账号成功"));
        AiAgentService service = new AiAgentService(properties, new ObjectMapper(), knowledge, tools);
        List<AiAgentService.StreamEvent> events = new ArrayList<>();
        AiChatRequest request = new AiChatRequest();
        request.setMessage(prompt);
        request.setPagePath("/");
        long started = System.nanoTime();
        try {
            service.stream(UUID.randomUUID().toString(), 123, request, List.of(), events::add);
            assertEquals("done", events.getLast().event());
            AiChatResult result = (AiChatResult) events.getLast().data();
            assertFalse(result.getAnswer().isBlank());
            assertTrue(events.stream().anyMatch(event -> "token".equals(event.event())));
            if (expectTool) {
                verify(tools).execute(eq("get_current_user"), any());
                assertTrue(result.getAnswer().contains("ai-smoke-user"));
                assertTrue(result.getTools().stream().anyMatch(tool -> "get_current_user".equals(tool.getName()) && tool.isSuccess()));
            } else {
                verifyNoInteractions(tools);
                if (prompt.contains("连接成功")) assertTrue(result.getAnswer().contains("连接成功"));
            }
            System.out.printf("DeepSeek Flash live test: tool=%s, elapsedMs=%d%n", expectTool,
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } finally {
            service.close();
        }
    }
}
