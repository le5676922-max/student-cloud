package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real backend HTTP + websocket only, using an isolated temporary database. No browser or external model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupportWorkflowTest {
    private static final Path WORK = temporary();
    private static final String MYSQL_URL = System.getenv("CLOUD_TEST_MYSQL_URL");
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry values) {
        boolean mysql = MYSQL_URL != null && !MYSQL_URL.isBlank();
        if (mysql && !MYSQL_URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3306/student_cloud_role_test_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only a dedicated local student_cloud_role_test_* schema is allowed");
        values.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        values.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:sqlite:" + WORK.resolve("support.db"));
        values.add("spring.datasource.username", () -> mysql ? System.getenv("CLOUD_TEST_MYSQL_USER") : "");
        values.add("spring.datasource.password", () -> mysql ? System.getenv("CLOUD_TEST_MYSQL_PASSWORD") : "");
        values.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        values.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        values.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("workspaces").toString());
        values.add("tongligong-student-cloud.ai.enabled", () -> "false");
    }

    @Test void membersEscalateToGlobalAdministratorWithDurableIsolatedRealtimeMessages() throws Exception {
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Support fixture", "username", "support-admin", "password", "Test-password-123"), null);
        String admin = login("support-admin");
        int ownerId = account("support-owner", admin);
        account("support-other", admin);
        String owner = login("support-owner"), other = login("support-other");
        JsonNode suggestion = ok(HttpMethod.POST, "/api/ai/chat", Map.of("message", "人工服务", "history", List.of()), owner);
        assertFalse(suggestion.path("supportSuggestion").path("reason").asText().isBlank());
        assertFalse(suggestion.path("answer").asText().contains("无法转接"));
        assertEquals(0, ok(HttpMethod.GET, "/api/support/conversations", null, owner).path("total").asInt(),
                "An AI handoff suggestion must not create a conversation before user confirmation");
        denied(HttpMethod.POST, "/api/support/socket-ticket", Map.of(), null);
        denied(HttpMethod.POST, "/api/support/conversations", message("No self tickets for admin", "admin-open"), admin);
        assertHandshakeDenied("ws://127.0.0.1:" + port + "/ws/support", null);
        assertHandshakeDenied("ws://127.0.0.1:" + port + "/ws/support?token=" + owner, null);

        String hostileTicket = ticket(owner);
        assertHandshakeDenied(socketUrl(hostileTicket), "https://untrusted.invalid");
        Listener ownerEvents = new Listener(), otherEvents = new Listener(), adminEvents = new Listener();
        String ownerTicket = ticket(owner);
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket ownerSocket = connect(client, ownerTicket, ownerEvents);
            WebSocket otherSocket = connect(client, ticket(other), otherEvents);
            WebSocket adminSocket = connect(client, ticket(admin), adminEvents);
            try {
                ownerEvents.awaitType("ready"); otherEvents.awaitType("ready"); adminEvents.awaitType("ready");
                assertHandshakeDenied(socketUrl(ownerTicket), null);
                JsonNode conversation = ok(HttpMethod.POST, "/api/support/conversations", message("AI没有解决上传失败问题", "initial-support"), owner);
                String id = conversation.path("id").asText();
                assertEquals("WAITING", conversation.path("status").asText());
                assertEquals(ownerId, conversation.path("ownerUserId").asInt());
                assertEquals(id, json.readTree(ownerEvents.awaitType("support.changed")).path("conversationId").asText());
                String notice = adminEvents.awaitType("support.changed");
                assertFalse(notice.contains("上传失败"), "notifications must contain no chat text");
                assertNull(otherEvents.events.poll(250, TimeUnit.MILLISECONDS), "another member must receive no notification");
                denied(HttpMethod.GET, "/api/support/conversations/" + id, null, other);
                denied(HttpMethod.GET, "/api/support/conversations/" + id + "/messages", null, other);
                denied(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("spoof", "foreign-send"), other);
                denied(HttpMethod.POST, "/api/support/conversations/" + id + "/accept", Map.of(), owner);
                assertEquals(0, ok(HttpMethod.GET, "/api/support/conversations", null, other).path("total").asInt());
                assertTrue(ok(HttpMethod.GET, "/api/support/conversations?status=WAITING", null, admin).path("total").asInt() > 0);

                JsonNode sent = ok(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("补充问题", "idempotent-message"), owner);
                JsonNode duplicate = ok(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("补充问题", "idempotent-message"), owner);
                assertEquals(sent.path("id"), duplicate.path("id"));
                denied(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("different", "idempotent-message"), owner);
                assertEquals(2, ok(HttpMethod.GET, "/api/support/conversations/" + id + "/messages", null, owner).path("items").size());
                assertEquals("ACTIVE", ok(HttpMethod.POST, "/api/support/conversations/" + id + "/accept", Map.of(), admin).path("status").asText());
                JsonNode reply = ok(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("请提供具体错误提示，不要发送密码。", "admin-reply"), admin);
                assertEquals("ADMIN", reply.path("senderRole").asText());
                assertEquals(1, ok(HttpMethod.GET, "/api/support/conversations/" + id + "/messages?afterId=" + sent.path("id").asLong(), null, owner).path("items").size());
                ownerEvents.awaitType("support.changed");
                ok(HttpMethod.POST, "/api/support/conversations/" + id + "/close", Map.of(), owner);
                denied(HttpMethod.POST, "/api/support/conversations/" + id + "/messages", message("closed", "after-close"), admin);
                assertEquals(3, ok(HttpMethod.GET, "/api/support/conversations/" + id + "/messages", null, owner).path("items").size());
                String next = ok(HttpMethod.POST, "/api/support/conversations", message("下一条问题", "new-conversation"), owner).path("id").asText();
                assertNotEquals(id, next);
                denied(HttpMethod.POST, "/api/support/conversations", message("x".repeat(2001), "overlong"), owner);

                // Logging out invalidates an already-connected websocket, not only future tickets.
                ok(HttpMethod.POST, "/user/logout", null, owner);
                ownerSocket.sendText("{\"type\":\"ping\"}", true).join();
                assertEquals(1008, ownerEvents.closed.get(5, TimeUnit.SECONDS));
                denied(HttpMethod.GET, "/api/support/conversations/" + id + "/messages", null, owner);
            } finally { ownerSocket.abort(); otherSocket.abort(); adminSocket.abort(); }
        }
    }

    private String login(String username) { return ok(HttpMethod.POST, "/user/login", Map.of("username", username, "password", "Test-password-123"), null).path("token").asText(); }
    private int account(String username, String token) { return ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", username, "password", "Test-password-123", "role", "USER", "tenantId", 1, "enable", true), token).path("id").asInt(); }
    private String ticket(String token) { return ok(HttpMethod.POST, "/api/support/socket-ticket", Map.of(), token).path("ticket").asText(); }
    private String socketUrl(String ticket) { return "ws://127.0.0.1:" + port + "/ws/support?ticket=" + ticket; }
    private WebSocket connect(HttpClient client, String ticket, Listener listener) throws Exception {
        return client.newWebSocketBuilder().header("Origin", "http://127.0.0.1:" + port)
                .buildAsync(URI.create(socketUrl(ticket)), listener).get(5, TimeUnit.SECONDS);
    }
    private void assertHandshakeDenied(String url, String origin) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var builder = client.newWebSocketBuilder(); if (origin != null) builder.header("Origin", origin);
            var failure = assertThrows(ExecutionException.class, () -> builder.buildAsync(URI.create(url), new Listener()).get(5, TimeUnit.SECONDS));
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            assertTrue(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode() >= 400);
        }
    }
    private static Map<String, Object> message(String content, String id) { return Map.of("content", content, "clientMessageId", id); }
    private JsonNode ok(HttpMethod method, String path, Object body, String token) {
        JsonNode result = request(method, path, body, token); assertEquals("0", result.path("code").asText(), method + " " + path + ": " + result); return result.path("data");
    }
    private void denied(HttpMethod method, String path, Object body, String token) { assertNotEquals("0", request(method, path, body, token).path("code").asText()); }
    private JsonNode request(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("axios-request", "true");
        if (token != null) headers.set("tongligong-student-cloud-token", token);
        JsonNode result = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody(); assertNotNull(result); return result;
    }
    private static Path temporary() { try { return Files.createTempDirectory("student-cloud-support-http-"); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    private static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>(); final CompletableFuture<Integer> closed = new CompletableFuture<>(); final StringBuilder partial = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data); if (last) { events.add(partial.toString()); partial.setLength(0); } socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        String awaitType(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) { String event = events.poll(100, TimeUnit.MILLISECONDS); if (event != null && event.contains("\"type\":\"" + type + "\"")) return event; }
            throw new AssertionError("Missing websocket event " + type);
        }
    }
}
