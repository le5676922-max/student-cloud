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
    @Autowired im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter rateLimit;
    @Autowired im.tlgxy.cloud.module.ai.config.AiAgentProperties aiProperties;
    @Autowired im.tlgxy.cloud.module.ai.service.AgentScheduleService agentSchedules;
    @Autowired im.tlgxy.cloud.module.learning.service.LearningLibraryService learningLibrary;
    @LocalServerPort int port;
    private String adminToken;
    private int storageId;

    @org.junit.jupiter.api.BeforeEach
    void isolateRateLimitWindows() {
        // Scenarios share application data, but not previous scenarios' localhost login counters.
        // The real limiter stays active throughout each scenario, including concurrent confirmations.
        var cache = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(rateLimit, "windows");
        assertNotNull(cache);
        cache.clear();
    }

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
        properties.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("shared-workspaces").toString());
        properties.add("tongligong-student-cloud.ai.enabled", () -> "false");
        // Statistics are deliberately eventually consistent; test the minimum supported real TTL.
        properties.add("tongligong-student-cloud.cache.overview-ttl", () -> "1s");
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
        assertEquals("hello cloud", downloadBody(download, token));
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(download, HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class).getStatusCode());
        HttpHeaders rangeHeaders = headers(token);
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=0-4");
        ResponseEntity<String> range = http.exchange(download, HttpMethod.GET, new HttpEntity<>(rangeHeaders), String.class);
        assertEquals(HttpStatus.PARTIAL_CONTENT, range.getStatusCode());
        assertEquals("hello", range.getBody());
        assertEquals("bytes 0-4/11", range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        JsonNode office = ok(HttpMethod.POST, "/onlyOffice/config/token",
                Map.of("storageKey", "workflow", "path", "/documents/renamed.txt"), token);
        String documentServerUrl = office.path("document").path("url").asText();
        assertTrue(documentServerUrl.contains("officeDownloadToken="));
        assertEquals("hello cloud", downloadBody(documentServerUrl, null));
        assertEquals("{\"error\":1}", http.exchange("/onlyOffice/callback", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", 2, "key", "synthetic"), headers(null)), String.class).getBody());

        JsonNode share = ok(HttpMethod.POST, "/api/share/create", Map.of("storageKey", "workflow", "sharePath", "/documents/", "shareEntries", List.of(Map.of("name", "renamed.txt", "type", "FILE")), "shareType", "FILE", "password", "1234"), token);
        String shareKey = share.path("shareKey").asText();
        assertFalse(shareKey.isBlank(), share.toString());
        assertNotEquals("0", request(HttpMethod.POST, "/api/share/files", Map.of("shareKey", shareKey, "path", "/"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/share/files", Map.of("shareKey", shareKey, "path", "/", "password", "1234"), null).path("code").asText());
        assertTrue(ok(HttpMethod.POST, "/api/share/files", Map.of("shareKey", shareKey, "path", "/", "password", "1234"), token).toString().contains("renamed.txt"));
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
    void globalAndWorkspaceRolesKeepBuiltInIdentitiesProtected() {
        JsonNode admin = ok(HttpMethod.GET, "/user/login/check", null, adminToken);
        assertEquals("ADMIN", admin.path("role").asText());
        assertTrue(admin.path("isAdmin").asBoolean());
        assertFalse(admin.has("isSuperAdmin"));
        JsonNode loggedOut = ok(HttpMethod.GET, "/user/login/check", null, null);
        assertFalse(loggedOut.hasNonNull("role"));
        assertFalse(loggedOut.path("isLogin").asBoolean());
        assertFalse(loggedOut.path("isAdmin").asBoolean());
        for (String role : List.of("ADMIN", "GUEST", "SUPER_ADMIN", "UNKNOWN")) {
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
            assertTrue(List.of("ADMIN", "TENANT_ADMIN", "USER").contains(account.path("role").asText()));
            assertNotEquals(0, account.path("id").asInt());
            assertNotEquals(2, account.path("id").asInt());
        }
    }

    @Test
    @Order(5)
    void noBusinessEndpointUsesHistoricalAnonymousStorageGrants() {
        // Simulate an old database whose former guest was enabled and granted read permission.
        jdbc.update("UPDATE user SET enable = 1 WHERE id = 2");
        jdbc.update("INSERT INTO user_storage_source (user_id, storage_source_id, root_path, enable, permissions) VALUES (2, ?, '/documents/', 1, 'preview,download')", storageId);
        for (String path : List.of("/admin/user/list", "/admin/storages", "/admin/tenants",
                "/api/share/info/synthetic", "/api/share/download/synthetic", "/s/synthetic",
                "/pd/workflow/documents/renamed.txt?signature=synthetic", "/api/site/config/userRootPath/workflow")) {
            ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(null)), JsonNode.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), path);
        }
        assertNotEquals("0", request(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workflow", "path", "/"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/site/config/storage", Map.of("storageKey", "workflow", "path", "/"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/share/verify", Map.of("shareKey", "synthetic", "password", "password"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", "workflow", "path", "/", "name", "guest-write"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/chat", Map.of("message", "hello"), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/api/ai/knowledge/search?question=upload", null, null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/enable/2", null, adminToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", 2, "username", "guest", "enable", true,
                "role", "USER", "tenantId", 1), adminToken).path("code").asText());
        assertFalse(ok(HttpMethod.GET, "/api/site/config/global", null, null).has("guest"));
        jdbc.update("DELETE FROM user_storage_source WHERE user_id = 2");
        jdbc.update("UPDATE user SET enable = 0 WHERE id = 2");
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

    @Test
    @Order(8)
    void agentAutomationRequiresConfirmationAndPreservesFilesAcrossFailures() throws Exception {
        Path root = Files.createDirectory(WORK.resolve("files/automation"));
        Files.writeString(root.resolve("01-notes.txt"), "database transaction");
        Files.writeString(root.resolve("02-photo.png"), "synthetic image");
        Files.writeString(root.resolve("03-notes.md"), "database index");
        Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("nested/ignored.txt"), "not recursive");
        int user = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "automation-user", "nickname", "Automation",
                "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1), adminToken).path("id").asInt();
        automationGrant(user, List.of("preview", "move", "newFolder"), "/automation/");
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "automation-user", "password", "Test-password-123"), null).path("token").asText();
        var request = Map.of("type", "ORGANIZE_FILES", "storageKey", "workflow", "path", "/");
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks", Map.of("type", "ORGANIZE_FILES", "storageKey", "workflow", "path", "/../documents"), token).path("code").asText());
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(Map.of("expression", "/automation/", "description", "protect parent", "mode", "inaccessible")), adminToken);
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks", request, token).path("code").asText());
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(), adminToken);
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/password", List.of(Map.of("expression", "/automation/", "password", "folder-test-only", "description", "protected")), adminToken);
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks", request, token).path("code").asText());
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/password", List.of(), adminToken);
        JsonNode preview = ok(HttpMethod.POST, "/api/ai/tasks", request, token);
        String id = preview.path("id").asText();
        assertEquals("PENDING", preview.path("status").asText());
        assertEquals(3, preview.path("steps").size());
        assertTrue(Files.exists(root.resolve("01-notes.txt")), "Preview must never move files");
        assertNotEquals("0", request(HttpMethod.GET, "/api/ai/tasks/" + id, null, null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/api/ai/tasks/" + id, null, adminToken).path("code").asText(), "Even an admin cannot confirm another user's task");
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks/" + id + "/confirm", Map.of("confirmed", false), token).path("code").asText());
        assertEquals("CANCELLED", ok(HttpMethod.POST, "/api/ai/tasks/" + id + "/cancel", null, token).path("status").asText());
        assertEquals("CANCELLED", ok(HttpMethod.POST, "/api/ai/tasks/" + id + "/confirm", Map.of("confirmed", true), token).path("status").asText());

        id = ok(HttpMethod.POST, "/api/ai/tasks", request, token).path("id").asText();
        automationGrant(user, List.of("preview"), "/automation/");
        assertEquals("FAILED", confirmAndAwaitTask(id, token).path("status").asText());
        assertTrue(Files.exists(root.resolve("01-notes.txt")));
        automationGrant(user, List.of("preview", "move", "newFolder"), "/automation/");

        id = ok(HttpMethod.POST, "/api/ai/tasks", request, token).path("id").asText();
        jdbc.update("UPDATE ai_task SET expires_at = 1 WHERE id = ?", id);
        assertEquals("EXPIRED", ok(HttpMethod.GET, "/api/ai/tasks/" + id, null, token).path("status").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks/" + id + "/confirm", Map.of("confirmed", true), token).path("code").asText());

        id = ok(HttpMethod.POST, "/api/ai/tasks", request, token).path("id").asText();
        Path differentRoot = Files.createDirectory(WORK.resolve("files/automation-other"));
        Files.copy(root.resolve("01-notes.txt"), differentRoot.resolve("01-notes.txt"), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        automationGrant(user, List.of("preview", "move", "newFolder"), "/automation-other/");
        assertEquals("FAILED", confirmAndAwaitTask(id, token).path("status").asText());
        assertTrue(Files.exists(differentRoot.resolve("01-notes.txt")), "Changing the user's root invalidates the old plan");
        automationGrant(user, List.of("preview", "move", "newFolder"), "/automation/");

        id = ok(HttpMethod.POST, "/api/ai/tasks", request, token).path("id").asText();
        Files.createDirectory(root.resolve("文档"));
        Files.writeString(root.resolve("文档/01-notes.txt"), "must not overwrite");
        assertEquals("FAILED", confirmAndAwaitTask(id, token).path("status").asText());
        assertEquals("must not overwrite", Files.readString(root.resolve("文档/01-notes.txt")));
        assertTrue(Files.exists(root.resolve("01-notes.txt")));

        String executable = ok(HttpMethod.POST, "/api/ai/tasks", request, token).path("id").asText();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> ok(HttpMethod.POST, "/api/ai/tasks/" + executable + "/confirm", Map.of("confirmed", true), token));
            var second = pool.submit(() -> ok(HttpMethod.POST, "/api/ai/tasks/" + executable + "/confirm", Map.of("confirmed", true), token));
            assertTrue(List.of("RUNNING", "SUCCEEDED").contains(first.get().path("status").asText()));
            assertTrue(List.of("RUNNING", "SUCCEEDED").contains(second.get().path("status").asText()));
        }
        JsonNode done = awaitTask(executable, token);
        assertEquals("SUCCEEDED", done.path("status").asText());
        assertEquals(2, done.path("results").size());
        assertTrue(Files.exists(root.resolve("图片/02-photo.png")));
        assertTrue(Files.exists(root.resolve("文档/03-notes.md")));
        assertTrue(Files.exists(root.resolve("nested/ignored.txt")));
        assertEquals(done, ok(HttpMethod.POST, "/api/ai/tasks/" + executable + "/confirm", Map.of("confirmed", true), token));

        int course = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "Agent imported", "semester", "2026"), token).path("id").asInt();
        id = ok(HttpMethod.POST, "/api/ai/tasks", Map.of("type", "IMPORT_LEARNING", "storageKey", "workflow", "path", "/文档/", "courseId", course, "tags", "自动归档"), token).path("id").asText();
        assertEquals("SUCCEEDED", confirmAndAwaitTask(id, token).path("status").asText());
        assertEquals(2, ok(HttpMethod.GET, "/api/learning/materials?courseId=" + course, null, token).size());
        assertTrue(Files.exists(root.resolve("文档/03-notes.md")), "Indexing does not move or delete files");
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/tasks", Map.of("type", "IMPORT_LEARNING", "storageKey", "workflow", "path", "/文档/", "courseId", course), adminToken).path("code").asText());
        ok(HttpMethod.DELETE, "/admin/user/delete/" + user, null, adminToken);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_task WHERE owner_user_id = ?", Integer.class, user));
        assertTrue(Files.exists(root.resolve("文档/03-notes.md")));
    }

    @Test
    @Order(9)
    void directoryPagesFilterBeforeCountingAndRejectStaleOrUnauthorizedCursors() throws Exception {
        Path folder = Files.createDirectory(WORK.resolve("files/paging"));
        for (int i = 0; i < 350; i++) Files.writeString(folder.resolve(String.format("notes-%03d.txt", i)), "synthetic notes");
        Files.writeString(folder.resolve("secret.txt"), "synthetic hidden file");
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(Map.of("expression", "/paging/secret.txt", "description", "hide", "mode", "hidden")), adminToken);
        var query = new java.util.HashMap<String, Object>();
        query.put("storageKey", "workflow"); query.put("path", "/paging/"); query.put("pageSize", 100);
        JsonNode first = ok(HttpMethod.POST, "/api/storage/files", query, adminToken);
        assertTrue(first.path("paged").asBoolean()); assertEquals(350, first.path("total").asInt());
        assertEquals(100, first.path("files").size()); assertEquals("notes-000.txt", first.path("files").get(0).path("name").asText());
        query.put("pageCursor", first.path("nextCursor").asText());
        JsonNode second = ok(HttpMethod.POST, "/api/storage/files", query, adminToken);
        assertEquals(100, second.path("offset").asInt()); assertEquals("notes-100.txt", second.path("files").get(0).path("name").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/storage/files", query, null).path("code").asText());
        Files.writeString(folder.resolve("notes-010.txt"), "changed length forces a new directory revision");
        assertEquals("41040", request(HttpMethod.POST, "/api/storage/files", query, adminToken).path("code").asText());
        query.remove("pageCursor"); query.put("nameQuery", "NOTES-349");
        JsonNode filtered = ok(HttpMethod.POST, "/api/storage/files", query, adminToken);
        assertEquals(1, filtered.path("total").asInt()); assertEquals("notes-349.txt", filtered.path("files").get(0).path("name").asText());
        query.put("pageSize", 501);
        assertNotEquals("0", request(HttpMethod.POST, "/api/storage/files", query, adminToken).path("code").asText());
        query.remove("pageSize"); query.remove("nameQuery");
        JsonNode legacy = ok(HttpMethod.POST, "/api/storage/files", query, adminToken);
        assertFalse(legacy.path("paged").asBoolean()); assertEquals(350, legacy.path("files").size());
        ok(HttpMethod.POST, "/admin/storage/" + storageId + "/filters", List.of(), adminToken);
    }

    @Test
    @Order(10)
    void streamingAndCancellationEndpointsDoNotAllowAnonymousAccess() {
        String id = java.util.UUID.randomUUID().toString();
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/chat/stream/" + id,
                Map.of("message", "hello", "history", List.of()), null).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/ai/chat/cancel/" + id, null, null).path("code").asText());
    }

    @Test
    @Order(11)
    void authenticatedHttpStreamKeepsSseFramingAndReturnsFinalAnswer() throws Exception {
        var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var modelRequests = new java.util.concurrent.atomic.AtomicInteger();
        provider.createContext("/chat/completions", exchange -> {
            JsonNode input = json.readTree(exchange.getRequestBody());
            if (input.path("stream").asBoolean()) modelRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String chunk = "data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0,
                    "delta", Map.of("content", "流式接口测试完成"))))) + "\n\n";
            exchange.getResponseBody().write(chunk.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        boolean enabled = aiProperties.isEnabled();
        String address = aiProperties.getBaseUrl(), key = aiProperties.getApiKey();
        try {
            provider.start();
            aiProperties.setEnabled(true);
            aiProperties.setBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort());
            aiProperties.setApiKey("local-fake-key");
            var response = http.exchange("/api/ai/chat/stream/" + java.util.UUID.randomUUID(), HttpMethod.POST,
                    new HttpEntity<>(Map.of("message", "测试回答", "history", List.of()), headers(adminToken)), String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getHeaders().getContentType());
            assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
            assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
            assertNotNull(response.getBody());
            assertTrue(response.getBody().contains("event: accepted\n"), response.getBody());
            assertTrue(response.getBody().contains("event: token\n"), response.getBody());
            assertTrue(response.getBody().contains("event: done\n"), response.getBody());
            assertTrue(response.getBody().contains("流式接口测试完成"), response.getBody());
            assertEquals(1, modelRequests.get());
            ok(HttpMethod.POST, "/api/ai/chat/cancel/" + java.util.UUID.randomUUID(), null, adminToken);
        } finally {
            aiProperties.setEnabled(enabled);
            aiProperties.setBaseUrl(address);
            aiProperties.setApiKey(key);
            provider.stop(0);
        }
    }

    @Test
    @Order(12)
    void agentRenameDuplicateReportAndWeeklyPreviewStayOwnerScoped() throws Exception {
        Path root = Files.createDirectory(WORK.resolve("files/agent-extension"));
        Files.writeString(root.resolve("first.txt"), "identical course text");
        Files.writeString(root.resolve("second.txt"), "identical course text");
        int user = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "automation-user", "nickname", "Automation",
                "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1), adminToken).path("id").asInt();
        automationGrant(user, List.of("preview", "rename", "move", "newFolder"), "/agent-extension/");
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", "automation-user", "password", "Test-password-123"), null).path("token").asText();

        JsonNode duplicate = ok(HttpMethod.GET, "/api/ai/tasks/duplicates?storageKey=workflow&path=/", null, token);
        assertEquals(1, duplicate.path("groups").size());
        assertEquals(2, duplicate.path("groups").get(0).path("paths").size());
        JsonNode plan = ok(HttpMethod.POST, "/api/ai/tasks", Map.of("type", "RENAME_BY_DATE", "storageKey", "workflow", "path", "/"), token);
        assertEquals(2, plan.path("steps").size());
        assertEquals("RENAME_FILE", plan.path("steps").get(0).path("action").asText());
        assertTrue(Files.exists(root.resolve("first.txt")), "Preview must not rename files");
        assertEquals("SUCCEEDED", confirmAndAwaitTask(plan.path("id").asText(), token).path("status").asText());
        assertFalse(Files.exists(root.resolve("first.txt")));

        JsonNode rule = ok(HttpMethod.POST, "/api/ai/schedules", Map.of("title", "Weekly tidy", "type", "ORGANIZE_FILES",
                "storageKey", "workflow", "path", "/", "dayOfWeek", 7, "hourOfDay", 21, "minuteOfHour", 0), token);
        String scheduleId = rule.path("id").asText();
        assertTrue(rule.path("enabled").asBoolean());
        assertFalse(ok(HttpMethod.GET, "/api/ai/schedules", null, adminToken).toString().contains(scheduleId));
        jdbc.update("UPDATE ai_schedule SET next_run_at = 1 WHERE id = ?", scheduleId);
        agentSchedules.generateDuePreviews();
        assertTrue(ok(HttpMethod.GET, "/api/ai/tasks", null, token).toString().contains("按文件类型整理目录"));
        try (var stream = Files.list(root)) { assertEquals(2, stream.filter(Files::isRegularFile).count(), "Scheduled trigger only previews"); }
        assertFalse(ok(HttpMethod.POST, "/api/ai/schedules/" + scheduleId + "/enabled?value=false", null, token).path("enabled").asBoolean());
        ok(HttpMethod.DELETE, "/admin/user/delete/" + user, null, adminToken);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_schedule WHERE owner_user_id = ?", Integer.class, user));
    }

    @Test
    @Order(13)
    void globalOverviewAndWorkspaceMemberManagementRemainSeparated() throws IOException, InterruptedException {
        JsonNode overview = ok(HttpMethod.GET, "/admin/overview", null, adminToken);
        assertTrue(overview.path("workspaces").asLong() >= 1);
        assertTrue(overview.path("storageSources").asLong() >= 1);
        assertNotEquals("0", request(HttpMethod.GET, "/api/workspace-admin/summary", null, adminToken).path("code").asText());

        int firstTenant = ok(HttpMethod.POST, "/admin/tenant", Map.of("name", "Managed workspace A", "enable", true), adminToken).path("id").asInt();
        int secondTenant = ok(HttpMethod.POST, "/admin/tenant", Map.of("name", "Managed workspace B", "enable", true), adminToken).path("id").asInt();
        int firstManager = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "tenant-manager-a",
                "nickname", "Manager A", "password", "Test-password-123", "enable", true,
                "role", "TENANT_ADMIN", "tenantId", firstTenant), adminToken).path("id").asInt();
        int secondManager = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "tenant-manager-b",
                "nickname", "Manager B", "password", "Test-password-123", "enable", true,
                "role", "TENANT_ADMIN", "tenantId", secondTenant), adminToken).path("id").asInt();
        Path managedFiles = Files.createDirectory(WORK.resolve("managed-workspace-files"));
        int managedStorage = ok(HttpMethod.POST, "/admin/storage", Map.of("name", "Managed storage", "key", "managed-storage",
                "type", "local", "tenantId", firstTenant, "enable", true, "orderNum", 3,
                "storageSourceAllParam", Map.of("filePath", managedFiles.toString(), "domain", "", "proxyPrivate", true, "proxyTokenTime", 1800)), adminToken).asInt();
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", firstManager, "username", "tenant-manager-a",
                "nickname", "Manager A", "enable", true, "role", "TENANT_ADMIN", "tenantId", firstTenant,
                "defaultPermissions", List.of("preview"), "userStorageSourceList", List.of(Map.of("storageSourceId", managedStorage,
                        "rootPath", "/", "enable", true, "permissions", List.of("preview")))), adminToken);
        String firstToken = ok(HttpMethod.POST, "/user/login", Map.of("username", "tenant-manager-a", "password", "Test-password-123"), null).path("token").asText();
        String secondToken = ok(HttpMethod.POST, "/user/login", Map.of("username", "tenant-manager-b", "password", "Test-password-123"), null).path("token").asText();
        JsonNode login = ok(HttpMethod.GET, "/user/login/check", null, firstToken);
        assertEquals("TENANT_ADMIN", login.path("role").asText());
        assertFalse(login.path("isAdmin").asBoolean());
        assertEquals(firstTenant, login.path("tenantId").asInt());
        for (String path : List.of("/admin/overview", "/admin/user/list", "/admin/storages", "/admin/tenants")) {
            assertNotEquals("0", request(HttpMethod.GET, path, null, firstToken).path("code").asText(), path);
        }
        assertEquals("Managed workspace A", ok(HttpMethod.GET, "/api/workspace-admin/summary", null, firstToken).path("tenantName").asText());
        assertNotEquals("0", request(HttpMethod.GET, "/api/workspace-admin/summary", null, null).path("code").asText());

        int firstMember = ok(HttpMethod.POST, "/api/workspace-admin/members", Map.of("username", "managed-member-a",
                "nickname", "Member A", "password", "Member-password-123"), firstToken).path("id").asInt();
        int secondMember = ok(HttpMethod.POST, "/api/workspace-admin/members", Map.of("username", "managed-member-b",
                "nickname", "Member B", "password", "Member-password-123"), secondToken).path("id").asInt();
        assertEquals("USER", ok(HttpMethod.GET, "/admin/user/" + firstMember, null, adminToken).path("role").asText());
        assertEquals(firstTenant, ok(HttpMethod.GET, "/admin/user/" + firstMember, null, adminToken).path("tenantId").asInt());
        assertEquals(managedStorage, ok(HttpMethod.GET, "/admin/user/" + firstMember, null, adminToken)
                .path("userStorageSourceList").get(0).path("storageSourceId").asInt());
        assertTrue(ok(HttpMethod.GET, "/api/workspace-admin/members?page=1", null, firstToken).toString().contains("managed-member-a"));
        assertFalse(ok(HttpMethod.GET, "/api/workspace-admin/members?page=1", null, firstToken).toString().contains("managed-member-b"));
        assertNotEquals("0", request(HttpMethod.POST, "/api/workspace-admin/members/" + secondMember + "/enabled?value=false", null, firstToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/workspace-admin/members/" + secondManager + "/enabled?value=false", null, firstToken).path("code").asText());
        assertNotEquals("0", request(HttpMethod.POST, "/api/workspace-admin/members/1/enabled?value=false", null, firstToken).path("code").asText());
        String memberToken = ok(HttpMethod.POST, "/user/login", Map.of("username", "managed-member-a", "password", "Member-password-123"), null).path("token").asText();
        assertNotEquals("0", request(HttpMethod.GET, "/api/workspace-admin/summary", null, memberToken).path("code").asText());
        assertFalse(ok(HttpMethod.POST, "/api/workspace-admin/members/" + firstMember + "/enabled?value=false", null, firstToken).path("enabled").asBoolean());
        assertNotEquals("0", request(HttpMethod.GET, "/api/learning/courses", null, memberToken).path("code").asText());
        assertTrue(ok(HttpMethod.POST, "/api/workspace-admin/members/" + firstMember + "/enabled?value=true", null, firstToken).path("enabled").asBoolean());
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", firstMember, "username", "managed-member-a",
                "nickname", "Member A", "enable", true, "role", "TENANT_ADMIN", "tenantId", firstTenant), adminToken);
        assertEquals("TENANT_ADMIN", ok(HttpMethod.GET, "/user/login/check", null, memberToken).path("role").asText());
        assertEquals(firstTenant, ok(HttpMethod.GET, "/api/workspace-admin/summary", null, memberToken).path("tenantId").asInt());
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", firstMember, "username", "managed-member-a",
                "nickname", "Member A", "enable", true, "role", "USER", "tenantId", firstTenant), adminToken);
        assertNotEquals("0", request(HttpMethod.GET, "/api/workspace-admin/summary", null, memberToken).path("code").asText());
        Thread.sleep(1100); // Latest counts are guaranteed after the configured 1-second summary TTL.
        assertTrue(ok(HttpMethod.GET, "/admin/overview", null, adminToken).path("workspaceAdmins").asLong() >= 2);
        assertEquals(firstManager, jdbc.queryForObject("SELECT id FROM user WHERE username = 'tenant-manager-a'", Integer.class));
    }

    @Test
    @Order(14)
    void shareSelectionsAndPreviouslyIssuedDownloadsRespectLiveRevocation() throws Exception {
        Path folder = Files.createDirectories(WORK.resolve("files/share-scope"));
        Files.writeString(folder.resolve("selected.txt"), "selected synthetic content");
        Files.writeString(folder.resolve("unselected.txt"), "unselected synthetic content");
        Files.createDirectories(folder.resolve("allowed/sub"));
        Files.writeString(folder.resolve("allowed/sub/deep.txt"), "deep synthetic content");
        Files.createDirectories(folder.resolve("hidden"));
        Files.writeString(folder.resolve("hidden/hidden.txt"), "hidden synthetic content");
        int owner = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "share-owner",
                "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1,
                "userStorageSourceList", List.of(Map.of("storageSourceId", storageId, "rootPath", "/share-scope",
                        "enable", true, "permissions", List.of("createShareLink", "download", "preview")))), adminToken).path("id").asInt();
        String ownerToken = ok(HttpMethod.POST, "/user/login", Map.of("username", "share-owner", "password", "Test-password-123"), null).path("token").asText();
        String key = ok(HttpMethod.POST, "/api/share/create", Map.of("storageKey", "workflow", "sharePath", "/",
                "shareEntries", List.of(Map.of("name", "selected.txt", "type", "FILE"), Map.of("name", "allowed", "type", "FOLDER")),
                "shareType", "FOLDER", "password", "a&+?=b"), ownerToken).path("shareKey").asText();
        JsonNode listed = ok(HttpMethod.POST, "/api/share/files", Map.of("shareKey", key, "path", "/", "password", "a&+?=b"), adminToken);
        assertEquals(2, listed.path("fileItemList").size());
        String issued = "";
        for (JsonNode item : listed.path("fileItemList")) if ("selected.txt".equals(item.path("name").asText())) issued = item.path("url").asText();
        assertFalse(issued.isBlank());
        assertEquals("selected synthetic content", downloadBody(issued, adminToken));
        JsonNode ownerOffice = ok(HttpMethod.POST, "/onlyOffice/config/token", Map.of("storageKey", "workflow", "path", "/selected.txt"), ownerToken);
        String ownerDocumentUrl = ownerOffice.path("document").path("url").asText();
        assertEquals("selected synthetic content", downloadBody(ownerDocumentUrl, null));
        JsonNode nested = ok(HttpMethod.POST, "/api/share/files", Map.of("shareKey", key, "path", "/allowed/sub", "password", "a&+?=b"), adminToken);
        String deep = nested.path("fileItemList").get(0).path("url").asText();
        assertEquals("deep synthetic content", downloadBody(deep, adminToken));
        assertShareDenied(HttpMethod.POST, "/api/share/files", Map.of("shareKey", key, "path", "/hidden", "password", "a&+?=b"));
        for (String path : List.of("/unselected.txt", "../unselected.txt", "%252e%252e/unselected.txt", "allowed\\deep.txt")) {
            String query = java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
            assertShareDenied(HttpMethod.GET, "/api/share/download/" + key + "?path=" + query + "&password=a%26%2B%3F%3Db", null);
        }
        ok(HttpMethod.POST, "/admin/user/disable/" + owner, null, adminToken);
        ResponseEntity<String> disabledOffice = http.exchange(ownerDocumentUrl, HttpMethod.GET, new HttpEntity<>(headers(null)), String.class);
        assertFalse(disabledOffice.getBody().contains("synthetic content"));
        assertShareDenied(HttpMethod.POST, "/api/share/files", Map.of("shareKey", key, "path", "/", "password", "a&+?=b"));
        assertShareDenied(HttpMethod.GET, issued, null);
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", owner, "username", "share-owner", "enable", true,
                "role", "USER", "tenantId", 1, "userStorageSourceList", List.of(Map.of("storageSourceId", storageId,
                        "rootPath", "/share-scope", "enable", true, "permissions", List.of("createShareLink", "download", "preview")))), adminToken);
        assertEquals("selected synthetic content", downloadBody(issued, adminToken));
        jdbc.update("UPDATE user_storage_source SET enable = 0 WHERE user_id = ? AND storage_source_id = ?", owner, storageId);
        assertShareDenied(HttpMethod.GET, issued, null);
        jdbc.update("UPDATE user_storage_source SET enable = 1, root_path = '/different' WHERE user_id = ? AND storage_source_id = ?", owner, storageId);
        assertShareDenied(HttpMethod.GET, issued, null);
        jdbc.update("UPDATE user_storage_source SET root_path = '/share-scope' WHERE user_id = ? AND storage_source_id = ?", owner, storageId);
        jdbc.update("UPDATE share_link SET share_item = 'broken' WHERE share_key = ?", key);
        assertShareDenied(HttpMethod.POST, "/api/share/files", Map.of("shareKey", key, "path", "/", "password", "a&+?=b"));
        assertShareDenied(HttpMethod.GET, issued, null);
    }

    private void assertShareDenied(HttpMethod method, String url, Object body) {
        ResponseEntity<String> response = http.exchange(url, method, new HttpEntity<>(body, headers(adminToken)), String.class);
        assertTrue(response.getStatusCode().is4xxClientError(), "Share boundary must fail with HTTP 4xx, not a redirect or server error");
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("synthetic content"));
    }

    private String downloadBody(String url, String token) {
        ResponseEntity<String> response = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getStatusCode() + ": " + response.getBody());
        return response.getBody();
    }

    private JsonNode confirmAndAwaitTask(String id, String token) throws InterruptedException {
        ok(HttpMethod.POST, "/api/ai/tasks/" + id + "/confirm", Map.of("confirmed", true), token);
        return awaitTask(id, token);
    }

    @Test
    @Order(15)
    void ordinaryFileWritesKeepPrivateIndexPathsAndManualRepairRechecksContent() throws Exception {
        Path root = WORK.resolve("files");
        Files.createDirectory(root.resolve("path-sync-fixture"));
        Files.createDirectory(root.resolve("path-sync-destination"));
        Files.writeString(root.resolve("path-sync-fixture/source.txt"), "path synchronization fixture");
        int course = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "Path sync", "semester", "2026"), adminToken).path("id").asInt();
        int material = ok(HttpMethod.POST, "/api/learning/materials", Map.of("courseId", course, "storageKey", "workflow",
                "path", "/path-sync-fixture/source.txt", "tags", ""), adminToken).path("id").asInt();
        // Disabled embeddings on refresh must clear nullable old vectors in SQL, not merely in a Java object.
        jdbc.update("UPDATE learning_material SET embedding_json='[1]', embedding_signature='old-model', semantic_segments=1 WHERE id=?", material);
        ok(HttpMethod.POST, "/api/learning/materials/" + material + "/refresh", null, adminToken);
        assertNull(jdbc.queryForObject("SELECT embedding_json FROM learning_material WHERE id=?", String.class, material));
        assertNull(jdbc.queryForObject("SELECT embedding_signature FROM learning_material WHERE id=?", String.class, material));
        ok(HttpMethod.POST, "/api/file/operator/rename/file", Map.of("storageKey", "workflow", "path", "/path-sync-fixture/",
                "name", "source.txt", "newName", "renamed.txt"), adminToken);
        assertEquals("/path-sync-fixture/renamed.txt", jdbc.queryForObject("SELECT path FROM learning_material WHERE id=?", String.class, material));
        ok(HttpMethod.POST, "/api/file/operator/rename/folder", Map.of("storageKey", "workflow", "path", "/",
                "name", "path-sync-fixture", "newName", "path-sync-renamed"), adminToken);
        assertEquals("/path-sync-renamed/renamed.txt", jdbc.queryForObject("SELECT path FROM learning_material WHERE id=?", String.class, material));
        JsonNode moved = ok(HttpMethod.POST, "/api/file/operator/move/file", Map.of("storageKey", "workflow", "path", "/path-sync-renamed/",
                "nameList", List.of("renamed.txt"), "targetPath", "/path-sync-destination/", "targetNameList", List.of("moved.txt")), adminToken);
        assertTrue(moved.get(0).path("success").asBoolean(), moved.toString());
        assertEquals("/path-sync-destination/moved.txt", jdbc.queryForObject("SELECT path FROM learning_material WHERE id=?", String.class, material));
        assertEquals("/path-sync-destination/moved.txt", ok(HttpMethod.GET, "/api/learning/search?query=fixture&courseId=" + course,
                null, adminToken).path("citations").get(0).path("path").asText());
        Files.move(root.resolve("path-sync-destination/moved.txt"), root.resolve("path-sync-destination/external.txt"));
        JsonNode repaired = ok(HttpMethod.POST, "/api/learning/materials/" + material + "/relink",
                Map.of("storageKey", "workflow", "path", "/path-sync-destination/external.txt"), adminToken);
        assertEquals("/path-sync-destination/external.txt", repaired.path("path").asText());
        assertFalse(repaired.has("content")); assertFalse(repaired.has("embeddingJson"));
        assertTrue(Files.exists(root.resolve("path-sync-destination/external.txt")));
    }

    @Test
    @Order(16)
    void semanticSqlFiltersWorkWithRealDatabaseWithoutExternalProviderCalls() throws Exception {
        var client = org.mockito.Mockito.mock(im.tlgxy.cloud.module.ai.service.LearningEmbeddingClient.class);
        org.mockito.Mockito.when(client.configured()).thenReturn(true);
        org.mockito.Mockito.when(client.dimensions()).thenReturn(32);
        org.mockito.Mockito.when(client.signature()).thenReturn("isolated-test-embedding-32");
        org.mockito.Mockito.when(client.minSimilarity()).thenReturn(0.25);
        org.mockito.Mockito.when(client.embed(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            List<String> input = call.getArgument(0);
            return input.stream().map(text -> { float[] vector = new float[32]; vector[0] = 1; return vector; }).toList();
        });
        Object previous = org.springframework.test.util.ReflectionTestUtils.getField(learningLibrary, "semantic");
        org.springframework.test.util.ReflectionTestUtils.setField(learningLibrary, "semantic",
                new im.tlgxy.cloud.module.learning.service.LearningSemanticIndex(client, json));
        try {
            Files.writeString(WORK.resolve("files/semantic-sql-fixture.txt"), "isolated semantic fixture with private notes");
            int course = ok(HttpMethod.POST, "/api/learning/courses", Map.of("name", "Semantic SQL", "semester", "2026"), adminToken).path("id").asInt();
            JsonNode indexed = ok(HttpMethod.POST, "/api/learning/materials", Map.of("courseId", course, "storageKey", "workflow",
                    "path", "/semantic-sql-fixture.txt", "tags", ""), adminToken);
            int id = indexed.path("id").asInt();
            assertEquals(1, indexed.path("semanticSegments").asInt());
            assertFalse(indexed.has("embeddingJson"));
            assertFalse(indexed.has("embeddingSignature"));
            JsonNode paraphrase = ok(HttpMethod.GET, "/api/learning/search?query=cell-structure&courseId=" + course, null, adminToken);
            assertEquals(1, paraphrase.path("citations").size(), paraphrase.toString());
            assertEquals(id, paraphrase.path("citations").get(0).path("materialId").asInt());
            org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).embed(org.mockito.ArgumentMatchers.anyList());
            // MySQL and SQLite must both discard large historical blobs before JDBC receives them.
            jdbc.update("UPDATE learning_material SET embedding_json=? WHERE id=?", "x".repeat(1024 * 1024 + 1), id);
            JsonNode bounded = ok(HttpMethod.GET, "/api/learning/search?query=cell-structure&courseId=" + course, null, adminToken);
            assertEquals(0, bounded.path("citations").size());
            assertTrue(bounded.path("notice").asText().contains("1MiB"));
            org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).embed(org.mockito.ArgumentMatchers.anyList());
            Files.writeString(WORK.resolve("files/semantic-sql-fixture.txt"), "changed private notes");
            assertEquals(0, ok(HttpMethod.GET, "/api/learning/search?query=notes&courseId=" + course, null, adminToken)
                    .path("citations").size());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(learningLibrary, "semantic", previous);
        }
    }

    private JsonNode awaitTask(String id, String token) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        JsonNode result;
        do {
            result = ok(HttpMethod.GET, "/api/ai/tasks/" + id, null, token);
            if (!"RUNNING".equals(result.path("status").asText())) return result;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Task did not complete within 10 seconds: " + result);
        return result;
    }

    private void automationGrant(int user, List<String> permissions, String root) {
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", user, "username", "automation-user", "nickname", "Automation", "enable", true,
                "role", "USER", "tenantId", 1, "userStorageSourceList", List.of(Map.of("storageSourceId", storageId, "rootPath", root, "enable", true, "permissions", permissions))), adminToken);
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
