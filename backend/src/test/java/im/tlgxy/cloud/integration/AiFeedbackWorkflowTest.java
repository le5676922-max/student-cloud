package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real authenticated HTTP + Flyway on a fresh SQLite database, no model or real user transcript. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiFeedbackWorkflowTest {
    private static final Path WORK = temporary();
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry values) {
        values.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        values.add("spring.datasource.url", () -> "jdbc:sqlite:" + WORK.resolve("feedback.db"));
        values.add("spring.datasource.username", () -> ""); values.add("spring.datasource.password", () -> "");
        values.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        values.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        values.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("workspaces").toString());
        values.add("tongligong-student-cloud.ai.enabled", () -> "false");
    }

    @Test void membersSubmitOnlyConfirmedQuestionAnswerAndGlobalAdministratorCuratesWithoutAutomaticTraining() {
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Feedback fixture", "username", "feedback-admin", "password", "Test-password-123"), null);
        String admin = login("feedback-admin");
        int memberId = account("feedback-member", "USER", admin);
        account("feedback-other", "USER", admin);
        account("feedback-manager", "TENANT_ADMIN", admin);
        String member = login("feedback-member"), other = login("feedback-other"), manager = login("feedback-manager");
        var payload = Map.of("clientMessageId", "feedback-one", "question", "上传失败怎么办？", "answer", "旧回答 password=synthetic-private-value",
                "pagePath", "/learning?token=must-not-store", "userId", 999, "tenantId", 999, "source", "serverVerified");
        denied(HttpMethod.POST, "/api/ai/feedback", payload, null);
        denied(HttpMethod.POST, "/api/ai/feedback", payload, admin);
        JsonNode submitted = ok(HttpMethod.POST, "/api/ai/feedback", payload, member);
        String id = submitted.path("id").asText(); assertTrue(id.matches("[a-f0-9]{32}"));
        assertEquals("OPEN", submitted.path("status").asText());
        assertFalse(submitted.has("answer")); assertFalse(submitted.has("userId"));
        assertEquals(id, ok(HttpMethod.POST, "/api/ai/feedback", payload, member).path("id").asText());
        denied(HttpMethod.POST, "/api/ai/feedback", Map.of("clientMessageId", "feedback-one", "question", "changed", "answer", "answer"), member);
        ok(HttpMethod.POST, "/api/ai/feedback", Map.of("clientMessageId", "manager-one", "question", "空间配置问题", "answer", "空间说明"), manager);

        for (String token : java.util.Arrays.asList(member, other, manager, null)) {
            denied(HttpMethod.GET, "/admin/ai-feedback", null, token);
            denied(HttpMethod.GET, "/admin/ai-feedback/" + id, null, token);
            denied(HttpMethod.GET, "/admin/ai-feedback/export", null, token);
            denied(HttpMethod.PUT, "/admin/ai-feedback/" + id, Map.of("status", "DISMISSED"), token);
        }
        JsonNode page = ok(HttpMethod.GET, "/admin/ai-feedback?page=1&pageSize=20&status=OPEN", null, admin);
        assertEquals(2, page.path("total").asInt()); assertEquals(2, page.path("items").size());
        assertFalse(page.toString().contains("synthetic-private-value"));
        assertFalse(page.path("items").get(0).has("answer"));
        JsonNode detail = ok(HttpMethod.GET, "/admin/ai-feedback/" + id, null, admin);
        assertEquals(memberId, detail.path("userId").asInt());
        assertEquals("userSubmitted", detail.path("source").asText());
        assertEquals("/learning", detail.path("pagePath").asText());
        assertFalse(detail.path("answer").asText().contains("synthetic-private-value"));
        denied(HttpMethod.PUT, "/admin/ai-feedback/" + id, Map.of("status", "RESOLVED", "correctedAnswer", " "), admin);
        denied(HttpMethod.PUT, "/admin/ai-feedback/" + id, Map.of("correctedAnswer", "missing status"), admin);
        JsonNode reviewed = ok(HttpMethod.PUT, "/admin/ai-feedback/" + id, Map.of("status", "RESOLVED", "correctedAnswer", "请检查当前目录的上传授权，再重试。", "note", "人工复核"), admin);
        assertEquals("RESOLVED", reviewed.path("status").asText());
        assertEquals(detail.path("answer"), reviewed.path("answer"), "The user-submitted original is not rewritten by review");
        JsonNode exported = ok(HttpMethod.GET, "/admin/ai-feedback/export", null, admin);
        assertEquals(1, exported.size());
        assertEquals("上传失败怎么办？", exported.get(0).path("question").asText());
        assertEquals("请检查当前目录的上传授权，再重试。", exported.get(0).path("answer").asText());
        var fields = new java.util.HashSet<String>(); exported.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("question", "answer"), fields);
        ok(HttpMethod.PUT, "/admin/ai-feedback/" + id, Map.of("status", "DISMISSED", "correctedAnswer", "", "note", "不进入知识库"), admin);
        assertTrue(ok(HttpMethod.GET, "/admin/ai-feedback/export", null, admin).isEmpty());
        ok(HttpMethod.DELETE, "/admin/user/delete/" + memberId, null, admin);
        denied(HttpMethod.GET, "/admin/ai-feedback/" + id, null, admin);
        assertEquals(1, ok(HttpMethod.GET, "/admin/ai-feedback", null, admin).path("total").asInt());
    }

    private int account(String username, String role, String admin) {
        return ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", username, "nickname", username, "password", "Test-password-123", "role", role, "tenantId", 1, "enable", true), admin).path("id").asInt();
    }
    private String login(String username) { return ok(HttpMethod.POST, "/user/login", Map.of("username", username, "password", "Test-password-123"), null).path("token").asText(); }
    private JsonNode ok(HttpMethod method, String path, Object body, String token) {
        JsonNode response = request(method, path, body, token); assertEquals("0", response.path("code").asText(), method + " " + path + ": " + response); return response.path("data");
    }
    private void denied(HttpMethod method, String path, Object body, String token) { assertNotEquals("0", request(method, path, body, token).path("code").asText()); }
    private JsonNode request(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("axios-request", "true");
        if (token != null) headers.set("tongligong-student-cloud-token", token);
        JsonNode response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody(); assertNotNull(response); return response;
    }
    private static Path temporary() { try { return Files.createTempDirectory("student-cloud-feedback-http-"); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
}
