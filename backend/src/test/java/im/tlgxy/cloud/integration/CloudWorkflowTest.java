package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, Flyway and local-storage regression test, always using a disposable database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWorkflowTest {
    private static final Path WORK = temporaryDirectory();
    private static final String MYSQL_URL = System.getenv("CLOUD_TEST_MYSQL_URL");
    private static final String TOKEN_HEADER = "tongligong-student-cloud-token";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String adminToken;
    private int storageId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        boolean mysql = MYSQL_URL != null && !MYSQL_URL.isBlank();
        if (mysql && !MYSQL_URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3306/student_cloud_role_test_[a-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated local student_cloud_role_test_* database may be used");
        }
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        properties.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:sqlite:" + WORK.resolve("test.db"));
        properties.add("spring.datasource.username", () -> mysql ? System.getenv("CLOUD_TEST_MYSQL_USER") : "");
        properties.add("spring.datasource.password", () -> mysql ? System.getenv("CLOUD_TEST_MYSQL_PASSWORD") : "");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        properties.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        properties.add("tongligong-student-cloud.ai.enabled", () -> "false");
    }

    @Test
    @Order(1)
    void installLoginUploadDownloadAndShare() throws Exception {
        assertFalse(ok(HttpMethod.GET, "/api/install/status", null, null).asBoolean());
        assertNotEquals("0", request(HttpMethod.POST, "/api/install", Map.of("username", "", "password", ""), null).path("code").asText());
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Workflow test", "username", "test-admin", "password", "Test-password-123"), null);
        assertTrue(ok(HttpMethod.GET, "/api/install/status", null, null).asBoolean());
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "test-admin", "password", "Test-password-123"), null).path("token").asText();
        assertFalse(token.isBlank());
        adminToken = token;
        assertNotEquals("0", request(HttpMethod.POST, "/api/install", Map.of("username", "another", "password", "Test-password-456"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/admin/storages", null, null).path("code").asText());

        Path storage = Files.createDirectory(WORK.resolve("files"));
        storageId = ok(HttpMethod.POST, "/admin/storage", Map.of(
                "name", "Integration storage", "key", "workflow", "type", "local",
                "enable", true, "enableFileOperator", true, "enableFileAnnoOperator", false,
                "orderNum", 1, "storageSourceAllParam", Map.of("filePath", storage.toString(), "domain", "", "proxyPrivate", true, "proxyTokenTime", 1800)), token).asInt();
        ok(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", "workflow", "path", "/", "name", "documents"), token);
        String uploadUrl = ok(HttpMethod.POST, "/api/file/operator/upload/file", Map.of("storageKey", "workflow", "path", "/documents/", "name", "hello.txt", "size", 11), token).asText();
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("hello cloud".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "hello.txt"; }
        });
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        JsonNode upload = http.exchange(uploadUrl, HttpMethod.PUT, new HttpEntity<>(form, headers), JsonNode.class).getBody();
        assertNotNull(upload);
        assertEquals("0", upload.path("code").asText(), upload.toString());
        assertEquals("hello cloud", Files.readString(storage.resolve("documents/hello.txt")));
        JsonNode files = ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/documents/"), token);
        assertTrue(files.toString().contains("hello.txt"), files.toString());

        ok(HttpMethod.POST, "/api/file/operator/rename/file", Map.of("storageKey", "workflow", "path", "/documents/", "name", "hello.txt", "newName", "renamed.txt"), token);
        assertTrue(Files.exists(storage.resolve("documents/renamed.txt")));
        JsonNode item = ok(HttpMethod.POST, "/api/storage/file/item", Map.of("storageKey", "workflow", "path", "/documents/renamed.txt"), token);
        String download = item.path("url").asText();
        assertFalse(download.isBlank(), item.toString());
        assertEquals("hello cloud", http.getForObject(download, String.class));

        JsonNode share = ok(HttpMethod.POST, "/api/share/create", Map.of("storageKey", "workflow", "sharePath", "/documents/", "shareEntries", List.of(Map.of("name", "renamed.txt", "type", "FILE")), "shareType", "FILE", "password", "1234"), token);
        String shareKey = share.path("shareKey").asText();
        assertFalse(shareKey.isBlank(), share.toString());
        assertNotEquals("0", request(HttpMethod.POST, "/api/share/files", Map.of("shareKey", shareKey, "path", "/"), null).path("code").asText());
        assertTrue(ok(HttpMethod.POST, "/api/share/files", Map.of("shareKey", shareKey, "path", "/", "password", "1234"), null).toString().contains("renamed.txt"));
        ok(HttpMethod.DELETE, "/api/share/" + shareKey, null, token);
        assertNotEquals("0", request(HttpMethod.GET, "/api/share/info/" + shareKey, null, null).path("code").asText());

        JsonNode copied = ok(HttpMethod.POST, "/api/file/operator/copy/file", Map.of("storageKey", "workflow", "path", "/documents/", "nameList", List.of("renamed.txt"), "targetPath", "/", "targetNameList", List.of("copied.txt")), token);
        assertTrue(Files.exists(storage.resolve("copied.txt")), copied.toString());
        ok(HttpMethod.POST, "/api/file/operator/move/file", Map.of("storageKey", "workflow", "path", "/", "nameList", List.of("copied.txt"), "targetPath", "/documents/", "targetNameList", List.of("moved.txt")), token);
        assertFalse(Files.exists(storage.resolve("copied.txt")));
        assertTrue(Files.exists(storage.resolve("documents/moved.txt")));
        ok(HttpMethod.POST, "/api/file/operator/delete/batch", Map.of("storageKey", "workflow", "deleteItems", List.of(Map.of("path", "/documents/", "name", "moved.txt", "type", "FILE"))), token);
        assertFalse(Files.exists(storage.resolve("documents/moved.txt")));
        assertNotEquals("0", request(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", "workflow", "path", "/", "name", "../outside"), token).path("code").asText());
    }

    @Test
    @Order(2)
    void ordinaryUserIsolationAndDisabledAccountsApplyToExistingSessions() {
        int tenantId = ok(HttpMethod.POST, "/admin/tenant", Map.of("name", "Isolated workspace", "enable", true), adminToken).path("id").asInt();
        int userId = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "workspace-admin", "nickname", "Workspace user", "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", tenantId), adminToken).path("id").asInt();
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "workspace-admin", "password", "Test-password-123"), null).path("token").asText();
        assertNotEquals("0", request(HttpMethod.GET, "/admin/storage/" + storageId, null, token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/"), token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/admin/storages", null, token).path("code").asText());
        ok(HttpMethod.POST, "/admin/tenant", Map.of("id", tenantId, "name", "Isolated workspace", "enable", false), adminToken);
        assertNotEquals("0", request(HttpMethod.GET, "/admin/storages", null, token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/user/login", Map.of("username", "workspace-admin", "password", "Test-password-123"), null).path("code").asText());
        ok(HttpMethod.POST, "/admin/tenant", Map.of("id", tenantId, "name", "Isolated workspace", "enable", true), adminToken);
        ok(HttpMethod.POST, "/admin/user/disable/" + userId, null, adminToken);
        assertNotEquals("0", request(HttpMethod.GET, "/admin/storages", null, token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/user/login", Map.of("username", "workspace-admin", "password", "Test-password-123"), null).path("code").asText());
        ok(HttpMethod.POST, "/user/logout", null, token);
    }

    @Test
    @Order(3)
    void protectBuiltInAdminAndRestrictAgentAndReadOnlyUser() {
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/copy", Map.of("fromId", 1, "toUsername", "extra-admin", "toNickname", "extra", "toPassword", "Test-password-123"), adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", 1, "username", "test-admin", "enable", false), adminToken).path("code").asText());
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "reader", "nickname", "Reader", "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1, "userStorageSourceList", List.of(Map.of("storageSourceId", storageId, "rootPath", "/documents/", "enable", true, "permissions", List.of("preview", "download")))), adminToken);
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "reader", "password", "Test-password-123"), null).path("token").asText();
        assertNotEquals("0", request(HttpMethod.GET, "/admin/user/list", null, token).path("code").asText());
        assertTrue(ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/"), token).toString().contains("renamed.txt"));
        assertNotEquals("0", request(HttpMethod.POST, "/api/file/operator/upload/file", Map.of("storageKey", "workflow", "path", "/", "name", "blocked.txt", "size", 1), token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/chat", Map.of("message", "hello"), null).path("code").asText());
        assertTrue(request(HttpMethod.POST, "/api/ai/chat", Map.of("message", "hello"), token).path("msg").asText().contains("未启用"));
        JsonNode knowledge = ok(HttpMethod.GET, "/api/ai/knowledge/search?question=upload", null, token);
        for (JsonNode document : knowledge) assertEquals("user", document.path("audience").asText());
    }

    @Test
    @Order(4)
    void exactlyThreeRolesAndBuiltInIdentitiesAreEnforcedAtTheApiBoundary() {
        JsonNode admin = ok(HttpMethod.GET, "/user/login/check", null, adminToken);
        assertEquals("ADMIN", admin.path("role").asText());
        assertTrue(admin.path("isAdmin").asBoolean());
        assertFalse(admin.has("isSuperAdmin"));
        JsonNode guest = ok(HttpMethod.GET, "/user/login/check", null, null);
        assertEquals("GUEST", guest.path("role").asText());
        assertFalse(guest.path("isLogin").asBoolean());
        assertFalse(guest.path("isAdmin").asBoolean());
        for (String role : List.of("ADMIN", "GUEST", "SUPER_ADMIN", "TENANT_ADMIN", "UNKNOWN")) {
            assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "forged-" + role,
                    "password", "Test-password-123", "enable", true, "role", role, "tenantId", 1), adminToken).path("code").asText(), role);
        }
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", 2,
                "username", "guest", "role", "USER", "enable", true), adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", 2,
                "username", "guest", "role", "GUEST", "enable", true, "password", "Test-password-123"), adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/user/login", Map.of("username", "guest", "password", ""), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/disable/1", null, adminToken).path("code").asText());
        for (JsonNode account : ok(HttpMethod.GET, "/admin/user/list", null, adminToken)) {
            assertTrue(List.of("ADMIN", "USER", "GUEST").contains(account.path("role").asText()));
            assertNotEquals(0, account.path("id").asInt());
        }
    }

    @Test
    @Order(5)
    void guestCanOnlyReadExplicitlySharedStorageAndCannotAdministerOrUseAgent() {
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", 2, "username", "guest", "nickname", "访客",
                "enable", true, "role", "GUEST", "tenantId", 1, "userStorageSourceList", List.of(Map.of(
                        "storageSourceId", storageId, "rootPath", "/documents/", "enable", true,
                        "permissions", List.of("preview", "download")))), adminToken);
        assertTrue(ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/"), null).toString().contains("renamed.txt"));
        for (String path : List.of("/admin/user/list", "/admin/storages", "/admin/tenant")) {
            assertNotEquals("0", request(HttpMethod.GET, path, null, null).path("code").asText());
        }
        assertNotEquals("0", request(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", "workflow", "path", "/", "name", "guest-write"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/chat", Map.of("message", "hello"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/api/ai/knowledge/search?question=upload", null, null).path("code").asText());
        ok(HttpMethod.POST, "/admin/user/disable/2", null, adminToken);
        assertNotEquals("0", request(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/"), null).path("code").asText());
        ok(HttpMethod.POST, "/admin/user/enable/2", null, adminToken);
    }

    @Test
    @Order(6)
    void ordinaryUserLifecycleAndAdminSettingsAreProtected() {
        int id = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "lifecycle", "nickname", "Lifecycle",
                "password", "Test-password-123", "enable", true), adminToken).path("id").asInt();
        JsonNode login = ok(HttpMethod.POST, "/user/login", Map.of("username", "lifecycle", "password", "Test-password-123"), null);
        assertEquals("USER", login.path("role").asText());
        assertFalse(login.path("admin").asBoolean());
        String token = login.path("token").asText();
        assertNotEquals("0", request(HttpMethod.GET, "/admin/config", null, token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", id,
                "username", "lifecycle", "role", "ADMIN", "enable", true), token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/user/updatePwd", Map.of("oldPassword", "wrong",
                "newPassword", "Changed-password-456", "confirmPassword", "Changed-password-456"), token).path("code").asText());
        ok(HttpMethod.POST, "/user/updatePwd", Map.of("oldPassword", "Test-password-123",
                "newPassword", "Changed-password-456", "confirmPassword", "Changed-password-456"), token);
        ok(HttpMethod.POST, "/user/logout", null, token);
        assertFalse(ok(HttpMethod.GET, "/user/login/check", null, token).path("isLogin").asBoolean());
        assertNotEquals("0", request(HttpMethod.POST, "/user/login", Map.of("username", "lifecycle", "password", "Test-password-123"), null).path("code").asText());
        ok(HttpMethod.POST, "/user/login", Map.of("username", "lifecycle", "password", "Changed-password-456"), null);
        int copyId = ok(HttpMethod.POST, "/admin/user/copy", Map.of("fromId", id, "toUsername", "copied-user",
                "toNickname", "Copied", "toPassword", "Copy-password-789"), adminToken).asInt();
        assertEquals("USER", ok(HttpMethod.GET, "/admin/user/" + copyId, null, adminToken).path("role").asText());
        ok(HttpMethod.DELETE, "/admin/user/delete/" + copyId, null, adminToken);
        ok(HttpMethod.DELETE, "/admin/user/delete/" + id, null, adminToken);
        assertNotEquals("0", request(HttpMethod.POST, "/user/login", Map.of("username", "lifecycle", "password", "Changed-password-456"), null).path("code").asText());
        assertTrue(ok(HttpMethod.GET, "/admin/config", null, adminToken).isObject());
    }

    @Test
    @Order(7)
    void learningLibraryChecksOwnershipFreshContentAndLivePermissions() throws Exception {
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "reader", "password", "Test-password-123"), null).path("token").asText();
        int readerId = ok(HttpMethod.GET, "/user/login/check", null, token).path("id").asInt();
        if (readerId == 0) readerId = jdbc.queryForObject("SELECT id FROM user WHERE username = 'reader'", Integer.class);
        assertNotEquals("0", request(HttpMethod.GET, "/api/learning/courses", null, null).path("code").asText());
        int adminCourse = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "Admin private", "semester", "2026 秋"), adminToken).path("id").asInt();
        int course = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "数据库", "semester", "2026 秋"), token).path("id").asInt();
        assertEquals(1, ok(HttpMethod.GET, "/api/learning/courses", null, token).size());
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
            assertNotEquals("0", request(method, "/api/learning/courses/" + adminCourse,
                    method == HttpMethod.PUT ? Map.of("name", "forged", "semester", "2026") : null, token).path("code").asText());
        }
        assertNotEquals("0", request(HttpMethod.GET, "/api/learning/materials?courseId=" + adminCourse, null, token).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/learning/courses", Map.of("name", " ", "semester", "2026"), token).path("code").asText());
        Map<String, Object> material = Map.of("courseId", course, "storageKey", "workflow", "path", "/renamed.txt", "tags", "期末，事务,期末");
        JsonNode indexed = ok(HttpMethod.POST, "/api/learning/materials", material, token);
        int id = indexed.path("id").asInt();
        assertFalse(indexed.has("content"));
        assertFalse(indexed.has("fingerprint"));
        assertEquals("期末,事务", indexed.path("tags").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/learning/materials", material, token).path("code").asText());
        JsonNode result = ok(HttpMethod.GET, "/api/learning/search?query=hello&courseId=" + course, null, token);
        assertEquals("hello cloud", result.path("citations").get(0).path("excerpt").asText());
        assertEquals("M" + id + "-S1", result.path("citations").get(0).path("reference").asText());
        assertEquals(0, ok(HttpMethod.GET, "/api/learning/search?query=hello", null, adminToken).path("citations").size());
        assertNotEquals("0", request(HttpMethod.POST, "/api/learning/materials/" + id + "/refresh", null, adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.DELETE, "/api/learning/materials/" + id, null, adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/learning/materials", Map.of("courseId", course,
                "storageKey", "workflow", "path", "/../outside.txt", "tags", ""), token).path("code").asText());
        assertEquals(0, ok(HttpMethod.GET, "/api/learning/search?query=no-such-topic", null, token).path("citations").size());

        Path file = WORK.resolve("files/documents/renamed.txt");
        var modified = Files.getLastModifiedTime(file);
        Files.writeString(file, "hello world"); // Same byte count and restored timestamp must still invalidate the index.
        Files.setLastModifiedTime(file, modified);
        assertSkipped(token);
        ok(HttpMethod.POST, "/api/learning/materials/" + id + "/refresh", null, token);
        assertEquals("hello world", ok(HttpMethod.GET, "/api/learning/search?query=hello", null, token).path("citations").get(0).path("excerpt").asText());

        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(Map.of("expression", "/documents/", "description", "hide parent", "mode", "inaccessible")), adminToken);
        assertSkipped(token);
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(), adminToken);
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/password", List.of(Map.of("expression", "/documents/", "password", "test-folder-only", "description", "protected")), adminToken);
        assertSkipped(token);
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/password", List.of(), adminToken);

        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", readerId, "username", "reader", "nickname", "Reader",
                "enable", true, "role", "USER", "tenantId", 1, "userStorageSourceList", List.of(Map.of(
                        "storageSourceId", storageId, "rootPath", "/documents/", "enable", true, "permissions", List.of()))), adminToken);
        assertSkipped(token);
        assertNotEquals("0", request(HttpMethod.POST, "/api/learning/materials/" + id + "/refresh", null, token).path("code").asText());
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", readerId, "username", "reader", "nickname", "Reader",
                "enable", true, "role", "USER", "tenantId", 1, "userStorageSourceList", List.of(Map.of(
                        "storageSourceId", storageId, "rootPath", "/documents/", "enable", true, "permissions", List.of("preview")))), adminToken);
        Files.delete(file);
        assertSkipped(token);
        Files.writeString(file, "hello world");
        ok(HttpMethod.DELETE, "/api/learning/materials/" + id, null, token);
        assertTrue(Files.exists(file), "Deleting an index must not delete the original file");
        ok(HttpMethod.POST, "/api/learning/materials", material, token);
        ok(HttpMethod.DELETE, "/api/learning/courses/" + course, null, token);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM learning_material WHERE course_id = ?", Integer.class, course));
        assertTrue(Files.exists(file));
        int cleanupCourse = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "cleanup", "semester", "2026"), token).path("id").asInt();
        ok(HttpMethod.POST, "/api/learning/materials", Map.of("courseId", cleanupCourse, "storageKey", "workflow", "path", "/renamed.txt", "tags", ""), token);
        ok(HttpMethod.DELETE, "/admin/user/delete/" + readerId, null, adminToken);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM learning_course WHERE owner_user_id = ?", Integer.class, readerId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM learning_material WHERE owner_user_id = ?", Integer.class, readerId));
    }

    private void assertSkipped(String token) {
        JsonNode result = ok(HttpMethod.GET, "/api/learning/search?query=hello", null, token);
        assertEquals(0, result.path("citations").size(), result.toString());
        assertEquals(1, result.path("skipped").asInt());
    }

    private JsonNode ok(HttpMethod method, String path, Object body, String token) {
        JsonNode response = request(method, path, body, token);
        assertEquals("0", response.path("code").asText(), method + " " + path + ": " + response);
        return response.path("data");
    }

    private JsonNode request(HttpMethod method, String path, Object body, String token) {
        JsonNode response = http.exchange(path, method, new HttpEntity<>(body, headers(token)), JsonNode.class).getBody();
        assertNotNull(response, method + " " + path);
        return response;
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("axios-request", "true");
        headers.set("axios-from", "http://127.0.0.1:" + port);
        if (token != null) headers.set(TOKEN_HEADER, token);
        return headers;
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("student-cloud-workflow-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
