package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.AiRagProperties;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProjectKnowledgeTest {
    private final ProjectKnowledgeService knowledge = new ProjectKnowledgeService(new AiRagProperties(), new ObjectMapper(), new DefaultResourceLoader());

    @ParameterizedTest
    @CsvSource({
            "怎么把文件存进网盘,user-upload-file",
            "如何创建课程归档学习资料,user-learning-library",
            "扫描PDF支持OCR吗,user-learning-formats",
            "资料修改后怎么重新索引,user-learning-refresh",
            "回答里的引用片段是PDF页码吗,user-learning-citations",
            "帮我按文件类型分类整理目录,user-agent-organize",
            "把资料批量索引到课程,user-agent-import",
            "任务过期重复确认部分失败怎么办,user-agent-task-status",
            "自动化能越权删除覆盖文件吗,user-agent-automation-security",
            "小芽临时附件分析和上传有什么区别,user-agent-attachments-privacy",
            "响应慢卡顿用了Redis会好吗,user-response-latency",
            "如何更新RAG项目帮助知识库,user-rag-maintenance"
    })
    void relevantBusinessHelpAppearsInTopFour(String query, String expectedId) {
        var results = knowledge.search(query, KnowledgeAudience.USER);
        assertTrue(results.stream().anyMatch(r -> expectedId.equals(r.getId())), () -> query + " -> " + results.stream().map(r -> r.getId()).toList());
        assertTrue(results.stream().allMatch(r -> r.getAudience().equals("user")));
    }

    @Test void unrelatedQuestionsAreNotGroundedInRandomHelp() {
        assertTrue(knowledge.search("火星轨道偏心率是多少", KnowledgeAudience.USER).isEmpty());
        assertTrue(knowledge.search("你好谢谢", KnowledgeAudience.USER).isEmpty());
    }

    @Test void generatedKnowledgeExactlyMatchesCanonicalSources() throws Exception {
        var mapper = new ObjectMapper();
        for (String audience : new String[]{"user", "admin"}) {
            var expected = mapper.readTree(Files.readString(Path.of("scripts/rag/" + audience + "-site-knowledge.json")));
            try (var runtime = getClass().getResourceAsStream("/ai/knowledge/" + audience + "-knowledge.json")) {
                assertNotNull(runtime);
                assertEquals(expected, mapper.readTree(runtime));
                assertTrue(expected.size() >= 60);
            }
        }
    }

    @Test void contextContainsActualSourceReferencesAndRespectsBudget() {
        String context = knowledge.buildContext("分类整理自动化任务", KnowledgeAudience.USER);
        assertTrue(context.contains("[K:user-"));
        assertTrue(context.contains("来源："));
        assertTrue(context.length() <= 5000);
        assertFalse(context.contains("[K:admin-"));
        assertFalse(knowledge.buildContext(knowledge.search("分类整理自动化任务", KnowledgeAudience.ADMIN), KnowledgeAudience.USER).contains("[K:admin-"));
    }
}
