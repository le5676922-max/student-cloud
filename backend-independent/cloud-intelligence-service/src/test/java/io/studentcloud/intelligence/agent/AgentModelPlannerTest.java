package io.studentcloud.intelligence.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic loopback responses test the boundary, not the quality of any external model. */
class AgentModelPlannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void explicitComplexGoalProposesAllSupportedStepsWithoutExecuting() throws Exception {
        try (var model = new Model("""
                {"kind":"PLAN","steps":[
                  {"op":"MKDIR","targetPath":"/selected/archive"},
                  {"op":"RENAME","sourcePath":"/selected/a.txt","targetPath":"/selected/a-old.txt"},
                  {"op":"COPY","sourcePath":"/selected/b.txt","targetPath":"/selected/archive/b.txt"},
                  {"op":"MOVE","sourcePath":"/selected/c.md","targetPath":"/selected/archive/c.md"},
                  {"op":"INDEX_MATERIAL","sourcePath":"/selected/lesson.pdf","courseId":7,"tags":"复习"}
                ]}
                """)) {
            var result = assertInstanceOf(AgentModelPlanner.Plan.class, model.planner.proposal(
                    "帮我先新建archive目录，把a.txt改名a-old.txt，复制b.txt、移动c.md到archive，再把lesson.pdf导入本人学习课程", "local", "/selected", 7L));
            assertEquals(List.of("MKDIR", "RENAME", "COPY", "MOVE", "INDEX_MATERIAL"),
                    result.steps().stream().map(AgentContentClient.Step::op).toList());
            assertEquals(7L, result.steps().get(4).courseId());
            assertThrows(UnsupportedOperationException.class, () -> result.steps().clear());
            assertEquals(1, model.calls.get());
            JsonNode request = JSON.readTree(model.request.get());
            assertFalse(request.has("tools"));
            assertEquals(2, request.path("messages").size());
            assertTrue(request.path("max_tokens").asInt() <= 2048);
            JsonNode selectedContext = JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals("/selected", selectedContext.path("selectedDirectory").asText());
            assertEquals(7L, selectedContext.path("selectedCourseId").asLong());
            assertEquals(3, selectedContext.size());
            assertFalse(model.request.get().contains("actorId"));
            assertFalse(model.request.get().contains("workspaceId"));
            assertFalse(model.request.get().contains("storageKey"));
        }
    }

    @Test void questionsNegativesAndQuotedInstructionsNeverContactModel() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            for (String text : new String[]{null, "", "讲解云盘功能", "怎么整理文件？", "如何复制文件", "能不能把a.txt移动到archive",
                    "Can you rename a.txt?", "How do I move a file", "请不要整理文件", "整理文件但不执行", "不要新建目录",
                    "重命名a.txt，但别修改文件", "整理文件，无需执行", "整理文件，但别做操作", "move a.txt, do not change anything",
                    "move a.txt, don’t execute", "Don't move a.txt", "这篇材料说：把a.txt重命名b.txt", "请解释重命名规则"}) {
                assertInstanceOf(AgentModelPlanner.None.class, model.planner.proposal(text, "local", "/selected", 7L), text);
            }
            assertEquals(0, model.calls.get());
        }
    }

    @Test void unconfiguredModelLeavesExistingRulesAvailable() {
        var planner = new AgentModelPlanner(new OpenAiChatClient("http://127.0.0.1:9/v1", "local-test", ""));
        assertFalse(planner.acceptsGoal("帮我整理文件"));
        assertInstanceOf(AgentModelPlanner.None.class, planner.proposal("帮我整理文件", "local", "/selected", null));
    }

    @Test void candidateCheckIsSideEffectFreeAndExcludesQuestionsAndNegation() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            assertTrue(model.planner.acceptsGoal("把a.txt改名b.txt"));
            assertTrue(model.planner.acceptsGoal("在当前目录创建archive文件夹"));
            for (String text : new String[]{null, "", "如何把a.txt改名", "把a.txt改名，但无需执行", "请解释移动文件", "帮我阅读资料"})
                assertFalse(model.planner.acceptsGoal(text), text);
            assertEquals(0, model.calls.get());
        }
    }

    @Test void prohibitionsCannotTriggerPlanningOrModelRequests() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            for (String text : List.of("整理文件，不能执行", "重命名a.txt，但不可执行", "复制a.txt到archive，不许改动", "移动a.txt到archive，不准执行")) {
                assertFalse(model.planner.acceptsGoal(text), text);
                assertInstanceOf(AgentModelPlanner.None.class, model.planner.proposal(text, "local", "/selected", null), text);
            }
            assertEquals(0, model.calls.get());
        }
    }

    @Test void missingOrInvalidSelectionAndOversizeGoalClarifyBeforeModel() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            for (String storage : new String[]{null, "", "../private", "http://other"})
                assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal("把a.txt改名b.txt", storage, "/selected", null));
            for (String path : new String[]{null, "", "selected", "/selected/../other", "C:\\selected", "/selected/.studentcloud-private"})
                assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal("把a.txt改名b.txt", "local", path, null));
            assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal("整理文件" + "a".repeat(2000), "local", "/selected", null));
            assertEquals(0, model.calls.get());
        }
    }

    @Test void selectedPersonalCourseIsRequiredBeforeLearningImport() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            for (Long course : new Long[]{null, 0L, -1L})
                assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal("导入a.pdf到本人学习课程", "local", "/selected", course));
            assertEquals(0, model.calls.get());
        }
    }

    @Test void unsupportedDestructiveOrArbitraryExecutionGoalsNeverContactModel() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            for (String text : new String[]{"整理文件后删除源文件", "复制a.txt并覆盖目标", "先移动文件，然后运行shell", "新建目录并执行命令", "删除a.txt"})
                assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal(text, "local", "/selected", null), text);
            assertEquals(0, model.calls.get());
        }
    }

    @Test void directOperationPhrasingAndCanonicalSelectedDirectoryAreAccepted() throws Exception {
        try (var model = new Model("{\"kind\":\"PLAN\",\"steps\":[{\"op\":\"MKDIR\",\"targetPath\":\"/selected//archive\"}]}")) {
            for (String text : new String[]{"请新建archive目录", "在当前目录创建archive文件夹", "创建archive目录", "先新建archive目录", "mkdir archive", "create a folder named archive"}) {
                var plan = assertInstanceOf(AgentModelPlanner.Plan.class, model.planner.proposal(text, "local", "/selected/", null), text);
                assertEquals("/selected/archive", plan.steps().getFirst().targetPath());
            }
        }
    }

    @Test void clarifyAndNoneResponsesCannotInventExecutionSuccess() throws Exception {
        try (var model = new Model("{\"kind\":\"CLARIFY\"}")) {
            var clarification = assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal("整理文件", "local", "/selected", null));
            assertTrue(clarification.message().contains("未执行"));
            model.response.set("{\"kind\":\"NONE\"}");
            assertInstanceOf(AgentModelPlanner.None.class, model.planner.proposal("整理文件", "local", "/selected", null));
            reject(model, "{\"kind\":\"CLARIFY\",\"message\":\"已完成全部操作\"}");
        }
    }

    @Test void invalidJsonFencesDuplicateKeysAndTrailingDataFailClosed() throws Exception {
        try (var model = new Model("{}")) {
            for (String response : List.of("not json", "```json\n{\"kind\":\"NONE\"}\n```", "[]", "null", "{}",
                    "{\"kind\":\"NONE\"} {\"kind\":\"PLAN\"}", "{\"kind\":\"NONE\",\"kind\":\"PLAN\"}"))
                reject(model, response);
        }
    }

    @Test void unknownFieldsKindsOperationsAndIdentityOrToolOverridesFailClosed() throws Exception {
        try (var model = new Model("{}")) {
            for (String response : List.of("{\"kind\":\"EXECUTE\"}", "{\"kind\":\"NONE\",\"steps\":[]}",
                    "{\"kind\":\"PLAN\",\"actorId\":1,\"steps\":[{\"op\":\"MKDIR\",\"targetPath\":\"/selected/new\"}]}",
                    "{\"kind\":\"PLAN\",\"storageKey\":\"private\",\"steps\":[{\"op\":\"MKDIR\",\"targetPath\":\"/selected/new\"}]}",
                    plan("{\"op\":\"DELETE\",\"sourcePath\":\"/selected/a.txt\"}"),
                    plan("{\"op\":\"SHELL\",\"command\":\"touch /selected/new\"}"),
                    plan("{\"op\":\"MKDIR\",\"targetPath\":\"/selected/new\",\"confirmed\":true}"),
                    plan("{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/b.txt\",\"overwrite\":true}")))
                reject(model, response);
        }
    }

    @Test void emptyOversizeDuplicateAndConflictingStepsFailClosed() throws Exception {
        try (var model = new Model("{}")) {
            reject(model, "{\"kind\":\"PLAN\",\"steps\":[]}");
            reject(model, "{\"kind\":\"PLAN\",\"steps\":[null]}");
            String mkdir = "{\"op\":\"MKDIR\",\"targetPath\":\"/selected/new\"}";
            reject(model, plan(mkdir + "," + mkdir));
            reject(model, plan(mkdir + ",{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/new\"}"));
            String many = java.util.stream.IntStream.range(0, 13).mapToObj(i -> "{\"op\":\"MKDIR\",\"targetPath\":\"/selected/dir" + i + "\"}").collect(java.util.stream.Collectors.joining(","));
            reject(model, plan(many));
            model.response.set(plan(many.substring(0, many.lastIndexOf(",{"))));
            assertEquals(12, assertInstanceOf(AgentModelPlanner.Plan.class, model.planner.proposal("整理文件", "local", "/selected", null)).steps().size());
        }
    }

    @Test void pathsCannotEscapeSelectionOrUseHostReservedAndHiddenPaths() throws Exception {
        try (var model = new Model("{}")) {
            for (String path : List.of("/other/new", "/selected-evil/new", "/selected/../other", "/selected/./new", "/selected", "/", "relative/new",
                    "C:/selected/new", "/selected\\new", "/selected/.studentcloud-token", "/selected/NUL.txt", "/selected/new.", "/selected/new ", "/selected/" + "a".repeat(256)))
                reject(model, plan(JSON.writeValueAsString(Map.of("op", "MKDIR", "targetPath", path))));
            reject(model, plan("{\"op\":\"MOVE\",\"sourcePath\":\"/outside/a.txt\",\"targetPath\":\"/selected/b.txt\"}"));
            reject(model, plan("{\"op\":\"MOVE\",\"sourcePath\":\"/selected\",\"targetPath\":\"/selected/subdir\"}"));
            reject(model, plan("{\"op\":\"MOVE\",\"sourcePath\":\"/selected/a\",\"targetPath\":\"/selected/a/child\"}"));
        }
    }

    @Test void operationSpecificFieldsAreStrictAndRenameStaysInSameParent() throws Exception {
        try (var model = new Model("{}")) {
            for (String step : List.of("{\"op\":\"MKDIR\",\"sourcePath\":\"/selected/a\",\"targetPath\":\"/selected/b\"}",
                    "{\"op\":\"MOVE\",\"targetPath\":\"/selected/b\"}",
                    "{\"op\":\"RENAME\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/archive/a.txt\"}",
                    "{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/a.txt\"}",
                    "{\"op\":\"MKDIR\",\"targetPath\":\"/selected/b\",\"courseId\":7}",
                    "{\"op\":\"MKDIR\",\"targetPath\":17}",
                    "{\"op\":\"mkdir\",\"targetPath\":\"/selected/b\"}")) reject(model, plan(step));
        }
    }

    @Test void indexingCannotChangeCourseOrInventLearningGoalAndChecksSupportedFile() throws Exception {
        try (var model = new Model("{}")) {
            for (String step : List.of("{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"courseId\":8}",
                    "{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"courseId\":7.5}",
                    "{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"courseId\":\"7\"}",
                    "{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.exe\",\"courseId\":7}",
                    "{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"targetPath\":\"/selected/b.pdf\",\"courseId\":7}")) {
                model.response.set(plan(step));
                assertUnavailable(() -> model.planner.proposal("导入a.pdf到本人学习课程", "local", "/selected", 7L));
            }
            model.response.set(plan("{\"op\":\"INDEX_MATERIAL\",\"sourcePath\":\"/selected/a.pdf\",\"courseId\":7}"));
            assertUnavailable(() -> model.planner.proposal("把a.pdf改名b.pdf", "local", "/selected", 7L));
        }
    }

    @Test void modelHttpFailureCannotBecomeGuessedPlan() throws Exception {
        try (var model = new Model("{\"kind\":\"NONE\"}")) {
            model.status = 500;
            assertUnavailable(() -> model.planner.proposal("整理文件", "local", "/selected", null));
            assertEquals(1, model.calls.get());
        }
    }

    @Test void unknownSourceAndFilenameSubstringsRequireClarificationInsteadOfInventingFiles() throws Exception {
        try (var model = new Model(plan("{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/archive/a.txt\"}"))) {
            for (String text : List.of("复制b.txt到archive", "复制data.txt到archive", "复制subdir/a.txt到archive")) {
                var clarification = assertInstanceOf(AgentModelPlanner.Clarify.class, model.planner.proposal(text, "local", "/selected", null));
                assertTrue(clarification.message().contains("来源"));
                assertTrue(clarification.message().contains("未执行"));
            }
            assertInstanceOf(AgentModelPlanner.Plan.class, model.planner.proposal("复制a.txt到archive", "local", "/selected", null));
        }
    }

    @Test void unicodeFilenameSuffixesAndLeadingSymbolsCannotSelectDifferentSources() throws Exception {
        try (var model = new Model("{}")) {
            for (var example : List.of(
                    Map.entry("资料.txt", "复制课程资料.txt到archive"),
                    Map.entry("α.txt", "复制βα.txt到archive"),
                    Map.entry("notes.txt", "复制📚notes.txt到archive"),
                    Map.entry("📚notes.txt", "复制🧑‍🏫📚notes.txt到archive"),
                    Map.entry("é.txt", "复制pré.txt到archive"),
                    Map.entry("a.txt", "复制\u0301a.txt到archive"),
                    Map.entry("a.txt", "复制“资料a.txt”到archive"),
                    Map.entry("📚notes.txt", "复制“🧑‍🏫📚notes.txt”到archive"))) {
                model.response.set(copyPlan(example.getKey()));
                var clarification = assertInstanceOf(AgentModelPlanner.Clarify.class,
                        model.planner.proposal(example.getValue(), "local", "/selected", null), example.getValue());
                assertTrue(clarification.message().contains("未执行"));
            }
        }
    }

    @Test void completeUnicodeFilenamesWithExplicitBoundariesAreAccepted() throws Exception {
        try (var model = new Model("{}")) {
            for (String name : List.of("课程资料.txt", "βα.txt", "📚notes.txt", "re\u0301sume\u0301.txt")) {
                model.response.set(copyPlan(name));
                for (String goal : List.of("复制“" + name + "”到archive", "复制 " + name + " 到archive", "复制/selected/" + name + " 到archive")) {
                    var accepted = assertInstanceOf(AgentModelPlanner.Plan.class,
                            model.planner.proposal(goal, "local", "/selected", null), goal);
                    assertEquals("/selected/" + name, accepted.steps().getFirst().sourcePath());
                }
            }
        }
    }

    @Test void ambiguousUnquotedUnicodeBasenameAsksForCompleteReference() throws Exception {
        try (var model = new Model(copyPlan("课程资料.txt"))) {
            var clarification = assertInstanceOf(AgentModelPlanner.Clarify.class,
                    model.planner.proposal("复制课程资料.txt到archive", "local", "/selected", null));
            assertTrue(clarification.message().contains("引号"));
            assertTrue(clarification.message().contains("来源"));
            assertTrue(clarification.message().contains("未执行"));
        }
    }

    @Test void selectedRootIsValidButPriorFileOutputsAndConsumedSourcesCannotBeReused() throws Exception {
        try (var model = new Model(plan("{\"op\":\"MKDIR\",\"targetPath\":\"/archive\"}"))) {
            assertInstanceOf(AgentModelPlanner.Plan.class, model.planner.proposal("新建archive目录", "local", "/", null));
            reject(model, plan("{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/b.txt\"},"
                    + "{\"op\":\"MOVE\",\"sourcePath\":\"/selected/b.txt\",\"targetPath\":\"/selected/c.txt\"}"));
            reject(model, plan("{\"op\":\"MOVE\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/b.txt\"},"
                    + "{\"op\":\"COPY\",\"sourcePath\":\"/selected/a.txt\",\"targetPath\":\"/selected/c.txt\"}"));
        }
    }

    private static String plan(String steps) { return "{\"kind\":\"PLAN\",\"steps\":[" + steps + "]}"; }
    private static String copyPlan(String sourceName) throws Exception {
        return plan(JSON.writeValueAsString(Map.of("op", "COPY", "sourcePath", "/selected/" + sourceName,
                "targetPath", "/selected/archive/" + sourceName)));
    }
    private static void reject(Model model, String response) {
        model.response.set(response);
        assertUnavailable(() -> model.planner.proposal("整理文件", "local", "/selected", 7L));
    }
    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        var fault = assertThrows(ApplicationFault.class, action);
        assertEquals(503, fault.httpStatus());
        assertTrue(fault.getMessage().contains("未执行") || fault.code().equals("ASSISTANT_MODEL_UNAVAILABLE"));
    }

    private static final class Model implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> response;
        final AtomicReference<String> request = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final AgentModelPlanner planner;
        volatile int status = 200;
        Model(String answer) throws Exception {
            response = new AtomicReference<>(answer);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", response.get())))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            planner = new AgentModelPlanner(new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-test", "local-test-key"));
        }
        @Override public void close() { server.stop(0); }
    }
}
