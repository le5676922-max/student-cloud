package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/auth/Flyway/controller coverage against disposable SQLite or explicitly isolated local MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResumableUploadWorkflowTest {
    static final Path WORK = temporaryDirectory();
    private static final String MYSQL_URL = System.getenv("CLOUD_RESUMABLE_TEST_MYSQL_URL");
    static final String PREFIX = "/api/uploads/resumable";
    static final String PASSWORD = "中文+ 100%";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    String admin, owner, other;
    int ownerId, storage;

    @DynamicPropertySource
    static void isolatedConfiguration(DynamicPropertyRegistry properties) {
        boolean mysql = MYSQL_URL != null && !MYSQL_URL.isBlank();
        if (mysql && !MYSQL_URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/student_cloud_role_test_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only a dedicated local student_cloud_role_test_* database may be used");
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        properties.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:sqlite:" + WORK.resolve("test.db"));
        properties.add("spring.datasource.username", () -> mysql ? System.getenv("CLOUD_RESUMABLE_TEST_MYSQL_USER") : "");
        properties.add("spring.datasource.password", () -> mysql ? System.getenv("CLOUD_RESUMABLE_TEST_MYSQL_PASSWORD") : "");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        properties.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        properties.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("workspaces").toString());
        properties.add("tongligong-student-cloud.uploads.resumable.staging-root", () -> WORK.resolve("staging").toString());
        properties.add("tongligong-student-cloud.registration.mail.enabled", () -> "false");
        properties.add("tongligong-student-cloud.ai.enabled", () -> "false");
        properties.add("tongligong-student-cloud.rate-limit.type", () -> "memory");
        properties.add("tongligong-student-cloud.cache.type", () -> "memory");
    }

    @Test @Order(1)
    void requiresLoginAndCompletesUnicodePasswordProtectedUpload() throws Exception {
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Resumable test", "username", "resumable-admin", "password", "Test-password-123"), null, null);
        admin = ok(HttpMethod.POST, "/user/login", Map.of("username", "resumable-admin", "password", "Test-password-123"), null, null).path("token").asText();
        Path cloud = Files.createDirectory(WORK.resolve("cloud")); Files.createDirectory(cloud.resolve("members"));
        storage = ok(HttpMethod.POST, "/admin/storage", Map.of("name", "Upload fixture", "key", "resumable-local", "type", "local", "enable", true,
                "storageSourceAllParam", Map.of("filePath", cloud.toString(), "domain", "", "proxyPrivate", true, "proxyTokenTime", 1800)), admin, null).asInt();
        ownerId = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "upload-owner", "nickname", "Fixture owner", "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1), admin, null).path("id").asInt();
        // Permission fixtures belong to this disposable test database, not the global profile API.
        jdbc.update("INSERT INTO user_storage_source(user_id,storage_source_id,root_path,enable,permissions) VALUES (?,?,?,1,'upload,newFolder,download,preview')", ownerId, storage, "/members/u" + ownerId);
        Files.createDirectories(cloud.resolve("members/u" + ownerId));
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "upload-other", "password", "Test-password-123", "enable", true, "role", "USER", "tenantId", 1), admin, null);
        owner = ok(HttpMethod.POST, "/user/login", Map.of("username", "upload-owner", "password", "Test-password-123"), null, null).path("token").asText();
        other = ok(HttpMethod.POST, "/user/login", Map.of("username", "upload-other", "password", "Test-password-123"), null, null).path("token").asText();
        ok(HttpMethod.POST, "/admin/storage/" + storage + "/password", List.of(Map.of("expression", "/members/**", "password", PASSWORD, "description", "Synthetic directory protection")), admin, null);
        byte[] content = "完整性和登录权限的测试文件".getBytes(StandardCharsets.UTF_8);
        var payload = Map.of("storageKey", "resumable-local", "path", "/", "name", "报告.txt", "size", content.length);
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(PREFIX + "/capabilities", HttpMethod.POST,
                new HttpEntity<>(payload, headers(null, PASSWORD)), String.class).getStatusCode());
        assertNotEquals("0", response(HttpMethod.POST, PREFIX + "/capabilities", payload, owner, null).path("code").asText());
        assertNotEquals("0", response(HttpMethod.POST, PREFIX + "/capabilities", payload, owner, "wrong").path("code").asText());
        assertTrue(ok(HttpMethod.POST, PREFIX + "/capabilities", payload, owner, PASSWORD).path("enabled").asBoolean());
        String id = init("报告.txt", content).path("id").asText();
        assertNotEquals("0", response(HttpMethod.GET, PREFIX + "/" + id, null, other, PASSWORD).path("code").asText());
        HttpHeaders headers = headers(owner, PASSWORD); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); headers.set("X-Upload-Chunk-SHA256", digest(content));
        JsonNode chunk = http.exchange(PREFIX + "/" + id + "/chunks/0", HttpMethod.PUT, new HttpEntity<>(content, headers), JsonNode.class).getBody();
        assertNotNull(chunk); assertEquals("0", chunk.path("code").asText(), chunk.toString());
        assertEquals(0, chunk.path("data").path("receivedChunks").get(0).asInt());
        assertEquals(id, init("报告.txt", content).path("id").asText());
        JsonNode completed = ok(HttpMethod.POST, PREFIX + "/" + id + "/complete", null, owner, PASSWORD);
        assertEquals("COMPLETED", completed.path("state").asText());
        assertTrue(completed.path("expiresAt").isIntegralNumber());
        assertArrayEquals(content, Files.readAllBytes(cloud.resolve("members/u" + ownerId + "/报告.txt")));
        assertEquals("COMPLETED", init("报告.txt", content).path("state").asText());
        ok(HttpMethod.DELETE, PREFIX + "/" + id, null, owner, PASSWORD);
        assertArrayEquals(content, Files.readAllBytes(cloud.resolve("members/u" + ownerId + "/报告.txt")));
        assertEquals(0L, jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM resumable_upload_session", Long.class));
    }

    @Test @Order(2)
    void liveGrantRevocationInvalidatesEveryResumableOperationWithoutWritingFiles() throws Exception {
        byte[] content = {1, 2, 3}; String id = init("revoked.bin", content).path("id").asText();
        jdbc.update("UPDATE user_storage_source SET enable=0 WHERE user_id=? AND storage_source_id=?", ownerId, storage);
        assertNotEquals("0", response(HttpMethod.GET, PREFIX + "/" + id, null, owner, PASSWORD).path("code").asText());
        assertNotEquals("0", response(HttpMethod.POST, PREFIX + "/" + id + "/complete", null, owner, PASSWORD).path("code").asText());
        assertNotEquals("0", response(HttpMethod.DELETE, PREFIX + "/" + id, null, owner, PASSWORD).path("code").asText());
        HttpHeaders headers = headers(owner, PASSWORD); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); headers.set("X-Upload-Chunk-SHA256", digest(content));
        JsonNode denied = http.exchange(PREFIX + "/" + id + "/chunks/0", HttpMethod.PUT, new HttpEntity<>(content, headers), JsonNode.class).getBody();
        assertNotNull(denied); assertNotEquals("0", denied.path("code").asText());
        assertFalse(Files.exists(WORK.resolve("cloud/members/u" + ownerId + "/revoked.bin")));
        assertEquals("", jdbc.queryForObject("SELECT received_mask FROM resumable_upload_session WHERE id=?", String.class, id));
        jdbc.update("UPDATE user_storage_source SET enable=1 WHERE user_id=? AND storage_source_id=?", ownerId, storage);
        assertEquals("OPEN", ok(HttpMethod.GET, PREFIX + "/" + id, null, owner, PASSWORD).path("state").asText());
        ok(HttpMethod.DELETE, PREFIX + "/" + id, null, owner, PASSWORD);
    }

    @Test @Order(3)
    void unknownCompletionStaysDurableAndCannotBeRepeatedViaInitOrComplete() throws Exception {
        byte[] content = {1}; String id = init("uncertain.bin", content).path("id").asText();
        jdbc.update("UPDATE resumable_upload_session SET state='COMPLETING' WHERE id=?", id);
        assertEquals("COMPLETING", ok(HttpMethod.GET, PREFIX + "/" + id, null, owner, PASSWORD).path("state").asText());
        assertEquals("COMPLETING", init("uncertain.bin", content).path("state").asText());
        assertEquals("COMPLETING", ok(HttpMethod.POST, PREFIX + "/" + id + "/complete", null, owner, PASSWORD).path("state").asText());
        assertFalse(Files.exists(WORK.resolve("cloud/members/u" + ownerId + "/uncertain.bin")));
        ok(HttpMethod.DELETE, PREFIX + "/" + id, null, owner, PASSWORD);
    }

    @Test @Order(4)
    void capacityRejectionBeforePublicationKeepsTheVerifiedChunksRetryable() throws Exception {
        byte[] bytes={7,8,9};String id=init("quota-retry.bin",bytes).path("id").asText();
        HttpHeaders header=headers(owner,PASSWORD);header.setContentType(MediaType.APPLICATION_OCTET_STREAM);header.set("X-Upload-Chunk-SHA256",digest(bytes));
        assertEquals("0",http.exchange(PREFIX+"/"+id+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(bytes,header),JsonNode.class).getBody().path("code").asText());
        jdbc.update("UPDATE user_storage_source SET quota_bytes=0 WHERE user_id=? AND storage_source_id=?",ownerId,storage);
        assertNotEquals("0",response(HttpMethod.POST,PREFIX+"/"+id+"/complete",null,owner,PASSWORD).path("code").asText());
        assertEquals("OPEN",ok(HttpMethod.GET,PREFIX+"/"+id,null,owner,PASSWORD).path("state").asText());
        assertFalse(Files.exists(WORK.resolve("cloud/members/u"+ownerId+"/quota-retry.bin")));
        jdbc.update("UPDATE user_storage_source SET quota_bytes=1048576 WHERE user_id=? AND storage_source_id=?",ownerId,storage);
        assertEquals("COMPLETED",ok(HttpMethod.POST,PREFIX+"/"+id+"/complete",null,owner,PASSWORD).path("state").asText());
        assertArrayEquals(bytes,Files.readAllBytes(WORK.resolve("cloud/members/u"+ownerId+"/quota-retry.bin")));
    }

    @Test @Order(5)
    void normalProxyUploadCannotBypassProtectedDirectoryByForgingAUrl() {
        var body=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        body.add("file",new org.springframework.core.io.ByteArrayResource(new byte[]{1,2,3}) { @Override public String getFilename(){return "protected.bin";} });
        HttpHeaders missing=headers(owner,null);missing.setContentType(MediaType.MULTIPART_FORM_DATA);
        String path="/file/upload/resumable-local/protected.bin";
        assertNotEquals("0",http.exchange(path,HttpMethod.PUT,new HttpEntity<>(body,missing),JsonNode.class).getBody().path("code").asText());
        assertFalse(Files.exists(WORK.resolve("cloud/members/u"+ownerId+"/protected.bin")));
        HttpHeaders correct=headers(owner,PASSWORD);correct.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertEquals("0",http.exchange(path,HttpMethod.PUT,new HttpEntity<>(body,correct),JsonNode.class).getBody().path("code").asText());
        assertArrayEquals(new byte[]{1,2,3},uncheckedFile("protected.bin"));
    }
    private byte[] uncheckedFile(String name) {try{return Files.readAllBytes(WORK.resolve("cloud/members/u"+ownerId+"/"+name));}catch(java.io.IOException error){throw new AssertionError(error);}}

    private JsonNode init(String name, byte[] content) throws Exception {
        String chunk = digest(content); String manifest = digest(chunk.getBytes(StandardCharsets.UTF_8));
        return ok(HttpMethod.POST, PREFIX + "/init", Map.of("storageKey", "resumable-local", "path", "/", "name", name, "size", content.length, "manifestHash", manifest, "chunkHashes", List.of(chunk)), owner, PASSWORD);
    }
    private JsonNode ok(HttpMethod method, String url, Object body, String token, String password) {
        JsonNode response = response(method, url, body, token, password);
        assertEquals("0", response.path("code").asText(), response.toString()); return response.path("data");
    }
    private JsonNode response(HttpMethod method, String url, Object body, String token, String password) {
        JsonNode response = http.exchange(url, method, new HttpEntity<>(body, headers(token, password)), JsonNode.class).getBody();
        assertNotNull(response); return response;
    }
    private static HttpHeaders headers(String token, String password) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("axios-request", "true"); // Match browser API calls: unauthorized returns 401, not the HTML login redirect.
        if (token != null) headers.set("tongligong-student-cloud-token", token);
        if (password != null) headers.set("X-Upload-Directory-Password", URLEncoder.encode(password, StandardCharsets.UTF_8).replace("+", "%20"));
        return headers;
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Path temporaryDirectory() { try { return Files.createTempDirectory("student-cloud-resumable-http-"); } catch (Exception failed) { throw new ExceptionInInitializerError(failed); } }
}
