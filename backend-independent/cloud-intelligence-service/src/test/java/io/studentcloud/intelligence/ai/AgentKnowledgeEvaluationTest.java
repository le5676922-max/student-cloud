package io.studentcloud.intelligence.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Versioned local retrieval scenarios; does not measure generative quality or real cloud performance. */
class AgentKnowledgeEvaluationTest {
    @TestFactory Stream<DynamicTest> roleScopedProductQuestions() throws Exception {
        var json = new ObjectMapper();
        var catalogue = json.readTree(getClass().getResourceAsStream("/ai/knowledge/project-help.json"));
        var fixture = json.readTree(getClass().getResourceAsStream("/ai/agent-knowledge-evaluation.json"));
        assertEquals(catalogue.path("revision").asText(), fixture.path("revision").asText(), "评测必须与知识版本一起更新");
        var ids = new HashSet<String>();
        catalogue.path("entries").forEach(entry -> ids.add(entry.path("id").asText()));
        var service = new ProjectKnowledgeService();
        return java.util.stream.StreamSupport.stream(fixture.path("cases").spliterator(), false).map(item ->
            DynamicTest.dynamicTest(item.path("role").asText() + ": " + item.path("query").asText(), () -> {
                Role role = Role.valueOf(item.path("role").asText());
                var actor = new Actor(3, "SC_EVAL", "evaluation", null, role, role == Role.ADMIN ? null : 7L);
                var hits = service.search(actor, item.path("query").asText(), 5);
                if (item.has("expected")) {
                    String expected = item.path("expected").asText();
                    assertTrue(ids.contains(expected), "评测引用不存在的知识ID");
                    assertTrue(hits.stream().anyMatch(hit -> hit.id().equals(expected)),
                            () -> "期望Top5包含" + expected + "，实际" + hits.stream().map(ProjectKnowledgeService.Hit::id).toList());
                }
                if (item.path("empty").asBoolean()) assertTrue(hits.isEmpty());
                if (item.has("forbidden")) assertTrue(hits.stream().noneMatch(hit -> hit.id().equals(item.path("forbidden").asText())));
            }));
    }
}
