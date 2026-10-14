package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import im.tlgxy.cloud.core.aspect.ApiLimitAspect;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP, Flyway and SMTP regression coverage; never points at an existing application database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationWorkflowTest {
    private static final Path WORK = temporaryDirectory();
    private static final String MYSQL_URL = System.getenv("CLOUD_REGISTRATION_TEST_MYSQL_URL");
    private static final String NONCE = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String PASSWORD = "Registration-password-123";
    private static final String TOKEN_HEADER = "tongligong-student-cloud-token";
    private static final LoopbackSmtp SMTP = new LoopbackSmtp();

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiLimitAspect rateLimit;
    @Autowired ApiRateLimiter sharedRateLimit;
    @LocalServerPort int port;

    private String globalToken;
    private Account managerA;
    private Account managerB;
    private String managerAToken;
    private String managerBToken;
    private int ownStorage;
    private int foreignStorage;
    private int sharedStorageA;
    private int sharedStorageB;
    private Account invitedMember;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        boolean mysql = MYSQL_URL != null && !MYSQL_URL.isBlank();
        if (mysql && (!MYSQL_URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3306/student_cloud_role_test_[a-z0-9_]+(?:\\?.*)?")
                || databaseName(MYSQL_URL).equals(databaseName(System.getenv("CLOUD_TEST_MYSQL_URL"))))) {
            throw new IllegalArgumentException("Registration tests require their own dedicated local student_cloud_role_test_* database");
        }
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        properties.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:sqlite:" + WORK.resolve("registration.db"));
        properties.add("spring.datasource.username", () -> mysql ? System.getenv("CLOUD_REGISTRATION_TEST_MYSQL_USER") : "");
        properties.add("spring.datasource.password", () -> mysql ? System.getenv("CLOUD_REGISTRATION_TEST_MYSQL_PASSWORD") : "");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        properties.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        properties.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("shared-workspaces").toString());
        properties.add("tongligong-student-cloud.ai.enabled", () -> "false");
        properties.add("tongligong-student-cloud.rate-limit.type", () -> "memory");
        properties.add("tongligong-student-cloud.registration.mail.enabled", () -> "true");
        properties.add("tongligong-student-cloud.registration.mail.host", () -> "127.0.0.1");
        properties.add("tongligong-student-cloud.registration.mail.port", SMTP::port);
        properties.add("tongligong-student-cloud.registration.mail.username", () -> "mailer@example.test");
        properties.add("tongligong-student-cloud.registration.mail.from", () -> "mailer@example.test");
        properties.add("tongligong-student-cloud.registration.mail.auth", () -> "false");
        properties.add("tongligong-student-cloud.registration.mail.ssl", () -> "false");
        properties.add("tongligong-student-cloud.registration.mail.starttls", () -> "false");
        properties.add("tongligong-student-cloud.registration.mail.code-secret", () -> "registration-integration-secret-with-more-than-32-characters");
    }

    @BeforeEach
    void isolateRateLimitWindows() {
        // Each scenario gets a new IP limit window; the limiter remains active within the scenario.
        Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(sharedRateLimit, "windows");
        assertNotNull(cache);
        cache.clear();
        SMTP.assertHealthy();
    }

    @AfterAll
    void closeSmtp() { SMTP.close(); }

    @Test
    @Order(1)
    void verifiedWorkspaceCreatorsOwnIsolatedWorkspacesAndLoginByEmailOrAccountId() throws Exception {
        assertFalse(ok(HttpMethod.GET, "/api/install/status", null, null).asBoolean());
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Registration integration", "username", "registration-root", "password", PASSWORD), null);
        globalToken = login("registration-root").path("token").asText();
        String legacyAdminAccount = jdbc.queryForObject("SELECT account_id FROM `user` WHERE id=1", String.class);
        assertTrue(login(legacyAdminAccount).path("admin").asBoolean());

        JsonNode configuration = ok(HttpMethod.GET, "/user/registration/config", null, null);
        assertTrue(configuration.path("enabled").asBoolean());
        assertTrue(configuration.path("emailVerificationRequired").asBoolean());
        assertTrue(configuration.path("mailAvailable").asBoolean());
        assertTrue(configuration.path("memberNameJoinRequiresApproval").asBoolean());
        assertFalse(configuration.has("codeSecret"));
        assertFalse(configuration.has("password"));

        String mixedCaseEmail = email("creator-a").toUpperCase(Locale.ROOT);
        managerA = registerWorkspace(mixedCaseEmail, "Registration A " + NONCE);
        managerB = registerWorkspace(email("creator-b"), "Registration B " + NONCE);
        assertEquals(mixedCaseEmail.toLowerCase(Locale.ROOT), managerA.email());
        assertNotEquals(managerA.accountId(), managerB.accountId());
        assertNotEquals(managerA.tenantId(), managerB.tenantId());
        assertTrue(managerA.accountId().matches("SC[A-F0-9]{16}"));
        assertEquals(managerA.id(), jdbc.queryForObject("SELECT owner_user_id FROM tenant WHERE id=?", Integer.class, managerA.tenantId()));
        assertEquals(managerB.id(), jdbc.queryForObject("SELECT owner_user_id FROM tenant WHERE id=?", Integer.class, managerB.tenantId()));

        JsonNode emailLogin = login("  " + mixedCaseEmail + "  ");
        JsonNode idLogin = login(managerA.accountId());
        assertEquals(managerA.accountId(), emailLogin.path("accountId").asText());
        assertEquals(managerA.accountId(), idLogin.path("accountId").asText());
        assertEquals("TENANT_ADMIN", idLogin.path("role").asText());
        assertFalse(idLogin.path("admin").asBoolean());
        managerAToken = idLogin.path("token").asText();
        managerBToken = login(managerB.email()).path("token").asText();
        sharedStorageA = sharedStorage(managerA);
        sharedStorageB = sharedStorage(managerB);
        assertNotEquals(sharedStorageA, sharedStorageB);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, managerA.id(), sharedStorageA));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id IN (1,2) AND storage_source_id IN (?,?)", Integer.class, sharedStorageA, sharedStorageB));
        String sharedKey = "workspace-" + managerA.tenantId();
        String otherKey = "workspace-" + managerB.tenantId();
        Path sharedFiles = WORK.resolve("shared-workspaces/tenant-" + managerA.tenantId());
        assertEquals(sharedFiles.toString(), jdbc.queryForObject("SELECT value FROM storage_source_config WHERE storage_id=? AND name='filePath'", String.class, sharedStorageA));
        assertTrue(Files.isDirectory(sharedFiles));
        ok(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", sharedKey, "path", "/", "name", "bootstrap"), managerAToken);
        upload(sharedKey, "/bootstrap/", "initial.txt", "New workspace manager can upload", managerAToken);
        assertEquals("New workspace manager can upload", Files.readString(sharedFiles.resolve("bootstrap/initial.txt")));
        ok(HttpMethod.POST, "/api/file/operator/rename/file", Map.of("storageKey", sharedKey, "path", "/bootstrap/", "name", "initial.txt", "newName", "renamed.txt"), managerAToken);
        assertTrue(Files.exists(sharedFiles.resolve("bootstrap/renamed.txt")));
        JsonNode item = ok(HttpMethod.POST, "/api/storage/file/item", Map.of("storageKey", sharedKey, "path", "/bootstrap/renamed.txt"), managerAToken);
        assertEquals("New workspace manager can upload", download(item.path("url").asText(), managerAToken));
        assertAnonymousDownloadDenied(item.path("url").asText());
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", otherKey, "path", "/"), managerAToken);
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", sharedKey, "path", "/"), managerBToken);
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", sharedKey, "path", "/"), globalToken);
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", sharedKey, "path", "/"), null);
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", sharedKey, "path", "/../tenant-" + managerB.tenantId()), managerAToken);
        ok(HttpMethod.POST, "/api/file/operator/delete/batch", Map.of("storageKey", sharedKey,
                "deleteItems", List.of(Map.of("path", "/bootstrap/", "name", "renamed.txt", "type", "FILE"))), managerAToken);
        assertFalse(Files.exists(sharedFiles.resolve("bootstrap/renamed.txt")));
        assertEquals("TENANT_ADMIN", jdbc.queryForObject("SELECT role FROM `user` WHERE id=?", String.class, managerA.id()));
        assertEquals(managerA.tenantName(), ok(HttpMethod.GET, "/api/workspace-admin/summary", null, managerAToken).path("tenantName").asText());
        assertEquals(managerB.tenantName(), ok(HttpMethod.GET, "/api/workspace-admin/summary", null, managerBToken).path("tenantName").asText());
        for (String path : List.of("/admin/overview", "/admin/user/list", "/admin/storages", "/admin/tenants", "/admin/config")) {
            denied(HttpMethod.GET, path, null, managerAToken);
        }
        for (String path : List.of("/api/workspace-admin/summary", "/api/workspace-admin/invitations", "/api/workspace-admin/join-requests")) {
            denied(HttpMethod.GET, path, null, globalToken);
            denied(HttpMethod.GET, path, null, null);
        }
        assertTrue(ok(HttpMethod.GET, "/admin/overview", null, globalToken).path("workspaceAdmins").asInt() >= 2);
    }

    @Test
    @Order(2)
    void invitationRegistersAnActiveMemberWithoutAcceptingPrivilegeOrForeignGrantInjection() throws Exception {
        ownStorage = storage("registration-own", managerA.tenantId(), "own-marker.txt");
        foreignStorage = storage("registration-foreign", managerB.tenantId(), "foreign-marker.txt");
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", managerA.id(), "username", managerA.accountId(),
                "nickname", "Registration A manager", "enable", true, "role", "TENANT_ADMIN", "tenantId", managerA.tenantId(),
                "defaultPermissions", List.of("preview", "download"), "userStorageSourceList", List.of(Map.of(
                "storageSourceId", ownStorage, "rootPath", "/", "enable", true, "permissions", List.of("preview", "download")), Map.of(
                "storageSourceId", sharedStorageA, "rootPath", "/", "enable", true, "permissions", List.of("preview", "download")))), globalToken);
        // Deliberately corrupt only the disposable test fixture to verify inheritance checks source tenancy.
        jdbc.update("INSERT INTO user_storage_source (user_id,storage_source_id,root_path,enable,permissions) VALUES (?,?,'/',1,'preview,download')", managerA.id(), foreignStorage);
        JsonNode invitation = invitation(managerAToken, 3);
        String rawCode = invitation.path("code").asText();
        assertTrue(rawCode.matches("WI_[A-Za-z0-9_-]{43}"));
        JsonNode listed = ok(HttpMethod.GET, "/api/workspace-admin/invitations", null, managerAToken);
        assertFalse(listed.toString().contains(rawCode), "Invitation lists must not disclose bearer codes");
        assertFalse(listed.toString().contains("tokenHash"));

        String email = email("invited");
        Map<String, Object> form = registration(email, code(email), "USER", "INVITE", rawCode);
        form.put("role", "ADMIN");
        form.put("tenantId", managerB.tenantId());
        form.put("enable", true);
        form.put("defaultPermissions", List.of("delete"));
        form.put("userStorageSourceList", List.of(Map.of("storageSourceId", foreignStorage, "rootPath", "/", "enable", true, "permissions", List.of("delete"))));
        invitedMember = account(ok(HttpMethod.POST, "/user/register", form, null));
        assertEquals("ACTIVE", invitedMember.status());
        assertEquals("USER", invitedMember.role());
        assertEquals(managerA.tenantId(), invitedMember.tenantId());
        assertEquals(1, jdbc.queryForObject("SELECT use_count FROM workspace_invitation WHERE id=?", Integer.class, invitation.path("id").asInt()));
        assertOwnGrants(invitedMember);
        JsonNode emailLogin = login(invitedMember.email());
        JsonNode idLogin = login(invitedMember.accountId());
        assertEquals(invitedMember.accountId(), emailLogin.path("accountId").asText());
        assertEquals("USER", idLogin.path("role").asText());
        String memberToken = idLogin.path("token").asText();
        assertTrue(ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "registration-own", "path", "/"), memberToken).toString().contains("own-marker.txt"));
        assertTrue(ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workspace-" + managerA.tenantId(), "path", "/"), memberToken).toString().contains("bootstrap"));
        denied(HttpMethod.POST, "/api/file/operator/upload/file", Map.of("storageKey", "workspace-" + managerA.tenantId(), "path", "/", "name", "read-only-denied.txt", "size", 1), memberToken);
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "registration-foreign", "path", "/"), memberToken);
        for (String path : List.of("/admin/user/list", "/api/workspace-admin/summary", "/api/workspace-admin/invitations", "/api/workspace-admin/join-requests")) {
            denied(HttpMethod.GET, path, null, memberToken);
        }
        assertTrue(ok(HttpMethod.GET, "/api/workspace-admin/members", null, managerAToken).toString().contains(invitedMember.email()));
        assertFalse(ok(HttpMethod.GET, "/api/workspace-admin/members", null, managerBToken).toString().contains(invitedMember.email()));
        assertMembershipStatus(invitedMember, "ACTIVE", true);
    }

    @Test
    @Order(3)
    void nameJoinStaysPendingUntilItsOwnWorkspaceManagerApprovesIt() throws Exception {
        Account pending = registerMember("pending", "NAME", managerA.tenantName());
        assertEquals("PENDING", pending.status());
        assertMembershipStatus(pending, "PENDING", false);
        assertEquals("USER", pending.role());
        assertEquals(0, jdbc.queryForObject("SELECT enable FROM `user` WHERE id=?", Integer.class, pending.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=?", Integer.class, pending.id()));
        assertEquals("", jdbc.queryForObject("SELECT default_permissions FROM `user` WHERE id=?", String.class, pending.id()));
        deniedLogin(pending.email());
        deniedLogin(pending.accountId());
        int requestId = joinRequestId(pending);
        assertTrue(ok(HttpMethod.GET, "/api/workspace-admin/join-requests", null, managerAToken).toString().contains(pending.accountId()));
        assertFalse(ok(HttpMethod.GET, "/api/workspace-admin/join-requests", null, managerBToken).toString().contains(pending.accountId()));
        denied(HttpMethod.POST, "/api/workspace-admin/members/" + pending.id() + "/enabled?value=true", null, managerAToken);
        denied(HttpMethod.POST, "/admin/user/enable/" + pending.id(), null, globalToken);
        denied(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", pending.id(), "username", pending.accountId(),
                "enable", true, "role", "USER", "tenantId", managerA.tenantId()), globalToken);
        denied(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", true), managerBToken);
        denied(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", true), globalToken);
        denied(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", true), login(invitedMember.email()).path("token").asText());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM workspace_join_request WHERE id=?", String.class, requestId));
        JsonNode approved = ok(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", true), managerAToken);
        assertEquals("APPROVED", approved.path("status").asText());
        assertMembershipStatus(pending, "APPROVED", true);
        assertEquals(managerA.id(), jdbc.queryForObject("SELECT reviewer_user_id FROM workspace_join_request WHERE id=?", Integer.class, requestId));
        assertOwnGrants(pending);
        String pendingToken = login(pending.accountId()).path("token").asText();
        assertTrue(ok(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workspace-" + managerA.tenantId(), "path", "/"), pendingToken).toString().contains("bootstrap"));
        assertEquals(pending.accountId(), login(pending.email()).path("accountId").asText());
        assertEquals(pending.accountId(), login(pending.accountId()).path("accountId").asText());
        denied(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", false), managerAToken);
        assertFalse(ok(HttpMethod.GET, "/api/workspace-admin/join-requests", null, managerAToken).toString().contains(pending.accountId()));
    }

    @Test
    @Order(4)
    void rejectedNameJoinCannotLoginOrBypassReviewUsingEnableEndpoints() throws Exception {
        Account rejected = registerMember("rejected", "NAME", managerA.tenantName());
        int requestId = joinRequestId(rejected);
        assertEquals("REJECTED", ok(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", false), managerAToken).path("status").asText());
        assertMembershipStatus(rejected, "REJECTED", false);
        deniedLogin(rejected.email());
        deniedLogin(rejected.accountId());
        denied(HttpMethod.POST, "/api/workspace-admin/members/" + rejected.id() + "/enabled?value=true", null, managerAToken);
        denied(HttpMethod.POST, "/admin/user/enable/" + rejected.id(), null, globalToken);
        denied(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", rejected.id(), "username", rejected.accountId(),
                "enable", true, "role", "USER", "tenantId", managerA.tenantId()), globalToken);
        denied(HttpMethod.POST, "/api/workspace-admin/join-requests/" + requestId + "/review", Map.of("approve", true), managerAToken);
        assertEquals(0, jdbc.queryForObject("SELECT enable FROM `user` WHERE id=?", Integer.class, rejected.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=?", Integer.class, rejected.id()));
    }

    @Test
    @Order(5)
    void registrationRejectsGlobalRolesDuplicateEmailAndInvalidInputWithoutCreatingAccounts() throws Exception {
        int initial = jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class);
        Map<String, Object> forbiddenRole = registration(email("forged-admin"), "123456", "ADMIN", null, null);
        forbiddenRole.put("workspaceName", "Forged " + NONCE);
        denied(HttpMethod.POST, "/user/register", forbiddenRole, null);
        denied(HttpMethod.POST, "/user/registration/email-code", Map.of("email", "not-an-email"), null);
        denied(HttpMethod.POST, "/user/registration/email-code", Map.of("email", "  "), null);
        denied(HttpMethod.POST, "/user/registration/email-code", Map.of("email", managerA.email().toUpperCase(Locale.ROOT)), null);
        denied(HttpMethod.POST, "/user/register", registration(managerA.email(), "123456", "USER", "NAME", managerA.tenantName()), null);

        String email = email("validation");
        String verification = code(email);
        Map<String, Object> form = registration(email, verification, "TENANT_ADMIN", null, null);
        form.put("workspaceName", "Validation " + NONCE);
        form.put("password", "short");
        form.put("confirmPassword", "short");
        denied(HttpMethod.POST, "/user/register", form, null);
        form.put("password", PASSWORD); form.put("confirmPassword", PASSWORD + "-different");
        denied(HttpMethod.POST, "/user/register", form, null);
        form.put("confirmPassword", PASSWORD); form.put("workspaceName", "  ");
        denied(HttpMethod.POST, "/user/register", form, null);
        form.put("workspaceName", "Validation " + NONCE); form.put("emailCode", "");
        denied(HttpMethod.POST, "/user/register", form, null);
        form.put("emailCode", verification); form.put("email", "");
        denied(HttpMethod.POST, "/user/register", form, null);
        assertEquals(initial, jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT consumed FROM email_verification WHERE email=?", Integer.class, email));
        denied(HttpMethod.POST, "/user/login", Map.of("username", managerA.email(), "password", ""), null);
        denied(HttpMethod.POST, "/user/login", Map.of("username", "", "password", PASSWORD), null);
        denied(HttpMethod.POST, "/user/login", Map.of("username", managerA.accountId(), "password", "Incorrect-password"), null);
        denied(HttpMethod.POST, "/user/login", Map.of("username", "guest", "password", PASSWORD), null);
    }

    @Test
    @Order(6)
    void wrongCodeAttemptsPersistAndSuccessfulCodeIsSingleUseEvenIfEnrollmentFails() throws Exception {
        String email = email("wrong-code");
        String actual = code(email);
        String wrong = "000000".equals(actual) ? "000001" : "000000";
        Map<String, Object> form = registration(email, wrong, "TENANT_ADMIN", null, null);
        form.put("workspaceName", "Wrong code " + NONCE);
        for (int attempt = 1; attempt <= 5; attempt++) {
            denied(HttpMethod.POST, "/user/register", form, null);
            assertEquals(attempt, jdbc.queryForObject("SELECT attempts FROM email_verification WHERE email=?", Integer.class, email));
        }
        form.put("emailCode", actual);
        denied(HttpMethod.POST, "/user/register", form, null);
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM email_verification WHERE email=?", Integer.class, email));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email=?", Integer.class, email));
        assertFalse(jdbc.queryForObject("SELECT code_digest FROM email_verification WHERE email=?", String.class, email).contains(actual), "Database contains only a keyed digest");

        String onceEmail = email("single-use");
        String onceCode = code(onceEmail);
        Map<String, Object> once = registration(onceEmail, onceCode, "USER", "NAME", "Missing workspace " + NONCE);
        denied(HttpMethod.POST, "/user/register", once, null);
        assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification WHERE email=?", Integer.class, onceEmail));
        assertEquals("", jdbc.queryForObject("SELECT code_digest FROM email_verification WHERE email=?", String.class, onceEmail));
        assertEquals("", jdbc.queryForObject("SELECT nonce FROM email_verification WHERE email=?", String.class, onceEmail));
        once.put("joinValue", managerA.tenantName());
        denied(HttpMethod.POST, "/user/register", once, null);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email=?", Integer.class, onceEmail));
    }

    @Test
    @Order(7)
    void emailCodeCooldownExpiryAndResendUseRealSmtpAndNeverExposeCodes() throws Exception {
        String email = email("expiry");
        String initialCode = code(email);
        int messagesBefore = SMTP.messageCount();
        denied(HttpMethod.POST, "/user/registration/email-code", Map.of("email", email.toUpperCase(Locale.ROOT)), null);
        assertEquals(messagesBefore, SMTP.messageCount());
        jdbc.update("UPDATE email_verification SET expires_at=1 WHERE email=?", email);
        Map<String, Object> form = registration(email, initialCode, "TENANT_ADMIN", null, null);
        form.put("workspaceName", "Expired code " + NONCE);
        denied(HttpMethod.POST, "/user/register", form, null);
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM email_verification WHERE email=?", Integer.class, email));
        jdbc.update("UPDATE email_verification SET resend_at=1 WHERE email=?", email);
        form.put("emailCode", code(email));
        Account renewed = account(ok(HttpMethod.POST, "/user/register", form, null));
        assertEquals("ACTIVE", renewed.status());
        assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification WHERE email=?", Integer.class, email));
        assertEquals(renewed.accountId(), login(renewed.email()).path("accountId").asText());
    }

    @Test
    @Order(8)
    void revokedExpiredAndExhaustedInvitationsCannotCreateMembers() throws Exception {
        JsonNode revoked = invitation(managerAToken, 1);
        int revokedId = revoked.path("id").asInt();
        denied(HttpMethod.DELETE, "/api/workspace-admin/invitations/" + revokedId, null, managerBToken);
        denied(HttpMethod.DELETE, "/api/workspace-admin/invitations/" + revokedId, null, globalToken);
        assertEquals(0, jdbc.queryForObject("SELECT revoked FROM workspace_invitation WHERE id=?", Integer.class, revokedId));
        ok(HttpMethod.DELETE, "/api/workspace-admin/invitations/" + revokedId, null, managerAToken);
        deniedRegistration("revoked-invite", revoked.path("code").asText());
        JsonNode expired = invitation(managerAToken, 1);
        jdbc.update("UPDATE workspace_invitation SET expires_at=1 WHERE id=?", expired.path("id").asInt());
        deniedRegistration("expired-invite", expired.path("code").asText());
        JsonNode once = invitation(managerAToken, 1);
        assertEquals("ACTIVE", registerMember("one-use", "INVITE", once.path("code").asText()).status());
        deniedRegistration("used-invite", once.path("code").asText());
        assertEquals(1, jdbc.queryForObject("SELECT use_count FROM workspace_invitation WHERE id=?", Integer.class, once.path("id").asInt()));
        denied(HttpMethod.POST, "/api/workspace-admin/invitations", Map.of("expiresInDays", 31, "maxUses", 1), managerAToken);
        denied(HttpMethod.POST, "/api/workspace-admin/invitations", Map.of("expiresInDays", 1, "maxUses", 0), managerAToken);
        assertFalse(ok(HttpMethod.GET, "/api/workspace-admin/invitations", null, managerBToken).toString().contains(revoked.path("code").asText()));
    }

    @Test
    @Order(9)
    void concurrentLastInvitationUseCommitsExactlyOneMemberAndOneConsumption() throws Exception {
        JsonNode invitation = invitation(managerAToken, 1);
        String rawCode = invitation.path("code").asText();
        String firstEmail = email("race-one");
        String secondEmail = email("race-two");
        Map<String, Object> first = registration(firstEmail, code(firstEmail), "USER", "INVITE", rawCode);
        Map<String, Object> second = registration(secondEmail, code(secondEmail), "USER", "INVITE", rawCode);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> a = executor.submit(() -> { start.await(); return request(HttpMethod.POST, "/user/register", first, null); });
            Future<JsonNode> b = executor.submit(() -> { start.await(); return request(HttpMethod.POST, "/user/register", second, null); });
            start.countDown();
            List<JsonNode> results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> "0".equals(value.path("code").asText())).count());
            JsonNode failed = results.stream().filter(value -> !"0".equals(value.path("code").asText())).findFirst().orElseThrow();
            assertTrue(failed.path("msg").asText().contains("邀请"), "The losing request must fail the invitation check");
        }
        assertEquals(1, jdbc.queryForObject("SELECT use_count FROM workspace_invitation WHERE id=?", Integer.class, invitation.path("id").asInt()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email IN (?,?)", Integer.class, firstEmail, secondEmail));
    }

    @Test
    @Order(10)
    void anInvitationStopsWorkingWhenItsIssuerOrWorkspaceIsDisabled() throws Exception {
        JsonNode issuerInvitation = invitation(managerBToken, 1);
        ok(HttpMethod.POST, "/admin/user/disable/" + managerB.id(), null, globalToken);
        try {
            deniedRegistration("disabled-issuer", issuerInvitation.path("code").asText());
            denied(HttpMethod.GET, "/api/workspace-admin/invitations", null, managerBToken);
            assertEquals(0, jdbc.queryForObject("SELECT use_count FROM workspace_invitation WHERE id=?", Integer.class, issuerInvitation.path("id").asInt()));
        } finally {
            ok(HttpMethod.POST, "/admin/user/enable/" + managerB.id(), null, globalToken);
        }
        JsonNode workspaceInvitation = invitation(managerBToken, 1);
        ok(HttpMethod.POST, "/admin/tenant", Map.of("id", managerB.tenantId(), "name", managerB.tenantName(), "enable", false), globalToken);
        try {
            deniedRegistration("disabled-workspace", workspaceInvitation.path("code").asText());
            deniedRegistrationByName("disabled-workspace-name", managerB.tenantName());
            assertEquals(0, jdbc.queryForObject("SELECT use_count FROM workspace_invitation WHERE id=?", Integer.class, workspaceInvitation.path("id").asInt()));
        } finally {
            ok(HttpMethod.POST, "/admin/tenant", Map.of("id", managerB.tenantId(), "name", managerB.tenantName(), "enable", true), globalToken);
        }
    }

    @Test
    @Order(11)
    void legacyMixedCaseEmailUsernamesStayReservedAndClientsCannotChooseAnotherPrincipal() throws Exception {
        String legacyName = "Legacy." + NONCE + "@Example.TEST";
        jdbc.update("UPDATE `user` SET username=?,email=NULL WHERE id=?", legacyName, invitedMember.id());
        assertEquals(invitedMember.accountId(), login(legacyName).path("accountId").asText());
        int before = SMTP.messageCount();
        denied(HttpMethod.POST, "/user/registration/email-code", Map.of("email", legacyName.toLowerCase(Locale.ROOT)), null);
        assertEquals(before, SMTP.messageCount());
        denied(HttpMethod.POST, "/api/workspace-admin/members", Map.of("username", "SC000000000001",
                "password", PASSWORD, "nickname", "Reserved alias"), managerAToken);
        denied(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "new-alias@example.test",
                "password", PASSWORD, "enable", true, "role", "USER", "tenantId", managerA.tenantId()), globalToken);
        JsonNode login = ok(HttpMethod.POST, "/user/login", Map.of("username", invitedMember.accountId(),
                "password", PASSWORD, "resolvedUserId", 1), null);
        assertEquals("USER", login.path("role").asText());
        assertEquals(invitedMember.accountId(), login.path("accountId").asText());
        denied(HttpMethod.GET, "/admin/user/list", null, login.path("token").asText());
    }

    @Test
    @Order(12)
    void simultaneousManualMemberCreationCannotProduceDuplicateLoginUsernames() throws Exception {
        String username = "manual-duplicate-" + NONCE;
        var body = Map.of("username", username, "password", PASSWORD, "nickname", "Concurrent member");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> first = executor.submit(() -> request(HttpMethod.POST, "/api/workspace-admin/members", body, managerAToken));
            Future<JsonNode> second = executor.submit(() -> request(HttpMethod.POST, "/api/workspace-admin/members", body, managerAToken));
            long successes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).stream()
                    .filter(result -> "0".equals(result.path("code").asText())).count();
            assertEquals(1, successes);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE username=?", Integer.class, username));
        assertEquals("USER", login(username).path("role").asText());
    }

    @Test
    @Order(13)
    void newSharedDriveLetsInvitedAndApprovedMembersUploadOnlyAfterTheirOwnWorkspaceAuthorizesThem() throws Exception {
        Account manager = registerWorkspace(email("shared-upload-manager"), "Registration shared upload " + NONCE);
        String token = login(manager.accountId()).path("token").asText();
        String storageKey = "workspace-" + manager.tenantId();
        int storage = sharedStorage(manager);
        Account invited = registerMember("shared-upload-invited", "INVITE", invitation(token, 1).path("code").asText());
        String invitedToken = login(invited.accountId()).path("token").asText();
        upload(storageKey, "/", "invited.txt", "Inherited workspace shared write access", invitedToken);
        Path files = WORK.resolve("shared-workspaces/tenant-" + manager.tenantId());
        assertEquals("Inherited workspace shared write access", Files.readString(files.resolve("invited.txt")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, invited.id(), storage));
        denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", "workspace-" + managerB.tenantId(), "path", "/"), invitedToken);
        Account pending = registerMember("shared-upload-pending", "NAME", manager.tenantName());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=?", Integer.class, pending.id()));
        deniedLogin(pending.accountId());
        ok(HttpMethod.POST, "/api/workspace-admin/join-requests/" + joinRequestId(pending) + "/review", Map.of("approve", true), token);
        String approvedToken = login(pending.accountId()).path("token").asText();
        upload(storageKey, "/", "approved.txt", "Approved membership acquires shared-drive access", approvedToken);
        assertEquals("Approved membership acquires shared-drive access", Files.readString(files.resolve("approved.txt")));
        String signedDownload = ok(HttpMethod.POST, "/api/storage/file/item", Map.of("storageKey", storageKey, "path", "/approved.txt"), approvedToken).path("url").asText();
        assertEquals("Approved membership acquires shared-drive access", download(signedDownload, approvedToken));
        assertAnonymousDownloadDenied(signedDownload);
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM `user` WHERE id=?", String.class, pending.id()));
        for (String session : List.of(token, invitedToken, approvedToken)) {
            denied(HttpMethod.POST, "/admin/overview/workspaces/" + manager.tenantId() + "/storage", null, session);
            denied(HttpMethod.GET, "/admin/overview", null, session);
        }
        ok(HttpMethod.POST, "/admin/tenant", Map.of("id", manager.tenantId(), "name", manager.tenantName(), "enable", false), globalToken);
        try {
            assertFalse(http.exchange(signedDownload, HttpMethod.GET, new HttpEntity<>(headers(approvedToken)), String.class)
                    .getStatusCode().is2xxSuccessful(), "A signed-in member must stop downloading from a disabled workspace");
            denied(HttpMethod.POST, "/api/file/operator/upload/file", Map.of("storageKey", storageKey, "path", "/", "name", "disabled.txt", "size", 1), invitedToken);
            denied(HttpMethod.POST, "/api/storage/files", Map.of("storageKey", storageKey, "path", "/"), approvedToken);
        } finally { ok(HttpMethod.POST, "/admin/tenant", Map.of("id", manager.tenantId(), "name", manager.tenantName(), "enable", true), globalToken); }
    }

    @Test
    @Order(14)
    void emailPasswordResetUsesRealLoopbackSmtpChangesHashAndInvalidatesExistingTokens() throws Exception {
        Account recovering = registerWorkspace(email("password-recovery"), "Password recovery " + NONCE);
        String firstToken = login(recovering.email()).path("token").asText();
        String secondToken = login(recovering.accountId()).path("token").asText();
        String oldHash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, recovering.id());
        JsonNode configuration = ok(HttpMethod.GET, "/user/password-reset/config", null, null);
        assertTrue(configuration.path("enabled").asBoolean());
        assertTrue(configuration.path("mailAvailable").asBoolean());
        assertFalse(configuration.toString().contains("codeSecret"));
        String code = resetCode(recovering.email().toUpperCase(Locale.ROOT));
        int sent = SMTP.messageCount();
        denied(HttpMethod.POST, "/user/password-reset/email-code", Map.of("email", recovering.email()), null);
        assertEquals(sent, SMTP.messageCount(), "Cooldown must precede SMTP");
        String newPassword = "  Recovery new password 123  ";
        Map<String, Object> body = Map.of("email", recovering.email(), "emailCode", code,
                "password", newPassword, "confirmPassword", newPassword, "userId", 1, "role", "ADMIN");
        ok(HttpMethod.POST, "/user/password-reset", body, null);
        String hash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, recovering.id());
        assertNotEquals(oldHash, hash);
        assertTrue(hash.startsWith("pbkdf2-sha256$"));
        assertNotEquals(newPassword, hash);
        assertEquals("TENANT_ADMIN", jdbc.queryForObject("SELECT role FROM `user` WHERE id=?", String.class, recovering.id()));
        for (String token : List.of(firstToken, secondToken)) {
            denied(HttpMethod.GET, "/api/workspace-admin/summary", null, token);
            assertFalse(ok(HttpMethod.GET, "/user/login/check", null, token).path("isLogin").asBoolean());
        }
        deniedLogin(recovering.email());
        JsonNode signedIn = ok(HttpMethod.POST, "/user/login", Map.of("username", recovering.accountId(), "password", newPassword), null);
        assertEquals(recovering.accountId(), signedIn.path("accountId").asText());
        ok(HttpMethod.GET, "/api/workspace-admin/summary", null, signedIn.path("token").asText());
        denied(HttpMethod.POST, "/user/password-reset", body, null);
        assertEquals(1, jdbc.queryForObject("SELECT consumed FROM email_verification WHERE email=? AND purpose='PASSWORD_RESET'", Integer.class, recovering.email()));
    }

    @Test
    @Order(15)
    void recoveryUnknownDisabledAndUnboundAccountsHaveIdenticalResultsAndNeverGainAccess() throws Exception {
        Account disabled = registerWorkspace(email("recovery-disabled"), "Recovery disabled " + NONCE);
        String disabledHash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, disabled.id());
        jdbc.update("UPDATE `user` SET enable=0 WHERE id=?", disabled.id());
        JsonNode disabledResult = request(HttpMethod.POST, "/user/password-reset", resetBody(disabled.email(), resetCode(disabled.email())), null);
        String unknown = email("recovery-unknown");
        JsonNode unknownResult = request(HttpMethod.POST, "/user/password-reset", resetBody(unknown, resetCode(unknown)), null);
        assertEquals(accountNeutralResult(disabledResult), accountNeutralResult(unknownResult), "No account-dependent response field may disclose account existence or state");
        assertEquals("0", disabledResult.path("code").asText());
        assertEquals(disabledHash, jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, disabled.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email=?", Integer.class, unknown));
        Account unbound = registerWorkspace(email("recovery-unbound"), "Recovery unbound " + NONCE);
        String unboundHash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, unbound.id());
        jdbc.update("UPDATE `user` SET email=NULL, username=? WHERE id=?", unbound.email(), unbound.id());
        JsonNode unboundResult = request(HttpMethod.POST, "/user/password-reset", resetBody(unbound.email(), resetCode(unbound.email())), null);
        assertEquals(accountNeutralResult(unknownResult), accountNeutralResult(unboundResult), "A legacy email-shaped username is not a verified email binding");
        assertEquals(unboundHash, jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, unbound.id()));
        deniedLogin(disabled.email());
    }

    @Test
    @Order(16)
    void registrationCodesCannotResetPasswordsAndOldCredentialMarkersAreRejected() throws Exception {
        Account manager = registerWorkspace(email("recovery-purpose"), "Recovery purpose " + NONCE);
        String token = login(manager.email()).path("token").asText();
        String registeredCode = SMTP.awaitCode(manager.email(), 0);
        denied(HttpMethod.POST, "/user/password-reset", resetBody(manager.email(), registeredCode), null);
        // Simulate a token issued after reset's logout using the old, already verified credential.
        String originalHash = jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, manager.id());
        jdbc.update("UPDATE `user` SET password=? WHERE id=?", originalHash + "-changed", manager.id());
        try {
            denied(HttpMethod.GET, "/api/workspace-admin/summary", null, token);
            assertFalse(ok(HttpMethod.GET, "/user/login/check", null, token).path("isLogin").asBoolean());
        } finally {
            jdbc.update("UPDATE `user` SET password=? WHERE id=?", originalHash, manager.id());
        }
    }

    private String resetCode(String email) throws Exception {
        int before = SMTP.messageCount();
        JsonNode response = request(HttpMethod.POST, "/user/password-reset/email-code", Map.of("email", email), null);
        assertEquals("0", response.path("code").asText(), "Reset code delivery must succeed: " + response);
        assertFalse(response.toString().contains("emailCode"));
        return SMTP.awaitCode(email.toLowerCase(Locale.ROOT), before);
    }

    private Map<String, Object> resetBody(String email, String code) {
        return Map.of("email", email, "emailCode", code, "password", "RecoveredPassword123", "confirmPassword", "RecoveredPassword123");
    }

    private JsonNode accountNeutralResult(JsonNode response) {
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) response.deepCopy();
        copy.remove("traceId"); // Independent per-request diagnostic correlation is expected to differ.
        return copy;
    }

    private int sharedStorage(Account manager) {
        return jdbc.queryForObject("SELECT id FROM storage_source WHERE tenant_id=? AND `key`=?", Integer.class, manager.tenantId(), "workspace-" + manager.tenantId());
    }

    private String download(String url, String token) {
        ResponseEntity<String> response = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "A signed-in authorized member can download");
        return response.getBody();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, token);
        return headers;
    }

    private void assertAnonymousDownloadDenied(String url) {
        HttpHeaders apiHeaders = new HttpHeaders();
        apiHeaders.set("axios-request", "true");
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.exchange(url, HttpMethod.GET, new HttpEntity<>(apiHeaders), String.class).getStatusCode());
        ResponseEntity<String> browser = http.getForEntity(url, String.class);
        assertEquals(HttpStatus.FOUND, browser.getStatusCode());
        assertNotNull(browser.getHeaders().getLocation());
        assertTrue(browser.getHeaders().getLocation().getPath().endsWith("/login"));
    }

    private void upload(String storageKey, String path, String name, String contents, String token) {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        String url = ok(HttpMethod.POST, "/api/file/operator/upload/file", Map.of("storageKey", storageKey, "path", path, "name", name, "size", bytes.length), token).asText();
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return name; } });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(TOKEN_HEADER, token);
        headers.set("axios-request", "true");
        headers.set("axios-from", "http://127.0.0.1:" + port);
        ResponseEntity<JsonNode> response = http.exchange(url, HttpMethod.PUT, new HttpEntity<>(form, headers), JsonNode.class);
        assertNotNull(response.getBody());
        assertEquals("0", response.getBody().path("code").asText(), "Workspace member upload must succeed: " + response.getBody());
    }

    private Account registerWorkspace(String email, String name) throws Exception {
        Map<String, Object> form = registration(email, code(email), "TENANT_ADMIN", null, null);
        form.put("workspaceName", name);
        Account value = account(ok(HttpMethod.POST, "/user/register", form, null));
        assertEquals("ACTIVE", value.status());
        assertEquals("TENANT_ADMIN", value.role());
        return value;
    }

    private Account registerMember(String label, String type, String value) throws Exception {
        String email = email(label);
        return account(ok(HttpMethod.POST, "/user/register", registration(email, code(email), "USER", type, value), null));
    }

    private void deniedRegistration(String label, String invitation) throws Exception {
        deniedRegistrationBy(label, "INVITE", invitation);
    }

    private void deniedRegistrationByName(String label, String name) throws Exception {
        deniedRegistrationBy(label, "NAME", name);
    }

    private void deniedRegistrationBy(String label, String type, String value) throws Exception {
        String email = email(label);
        denied(HttpMethod.POST, "/user/register", registration(email, code(email), "USER", type, value), null);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email=?", Integer.class, email));
    }

    private JsonNode invitation(String token, int uses) {
        return ok(HttpMethod.POST, "/api/workspace-admin/invitations", Map.of("expiresInDays", 7, "maxUses", uses), token);
    }

    private Map<String, Object> registration(String email, String code, String type, String joinType, String joinValue) {
        Map<String, Object> form = new HashMap<>();
        form.put("email", email); form.put("emailCode", code);
        form.put("password", PASSWORD); form.put("confirmPassword", PASSWORD);
        form.put("nickname", "Registration fixture"); form.put("registrationType", type);
        if (joinType != null) form.put("joinType", joinType);
        if (joinValue != null) form.put("joinValue", joinValue);
        return form;
    }

    private Account account(JsonNode value) {
        String email = value.path("email").asText();
        int id = jdbc.queryForObject("SELECT id FROM `user` WHERE email=?", Integer.class, email);
        assertFalse(value.has("password")); assertFalse(value.has("salt")); assertFalse(value.has("emailCode"));
        return new Account(id, value.path("accountId").asText(), email, value.path("role").asText(),
                value.path("tenantId").asInt(), value.path("tenantName").asText(), value.path("status").asText());
    }

    private int joinRequestId(Account user) {
        return jdbc.queryForObject("SELECT id FROM workspace_join_request WHERE user_id=?", Integer.class, user.id());
    }

    private void assertMembershipStatus(Account account, String expectedStatus, boolean expectedEnabled) {
        JsonNode page = ok(HttpMethod.GET, "/api/workspace-admin/members", null, managerAToken);
        for (JsonNode member : page.path("items")) {
            if (member.path("id").asInt() != account.id()) continue;
            assertEquals(expectedStatus, member.path("membershipStatus").asText());
            assertTrue(member.has("enabled"), "The existing enabled field must remain available");
            assertEquals(expectedEnabled, member.path("enabled").asBoolean());
            return;
        }
        fail("The workspace member page must include " + account.accountId());
    }

    private void assertOwnGrants(Account member) {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, member.id(), ownStorage));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, member.id(), foreignStorage));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, member.id(), sharedStorageA));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE user_id=? AND storage_source_id=?", Integer.class, member.id(), sharedStorageB));
    }

    private int storage(String key, int tenant, String marker) throws IOException {
        Path files = Files.createDirectory(WORK.resolve(key));
        Files.writeString(files.resolve(marker), "synthetic registration fixture");
        return ok(HttpMethod.POST, "/admin/storage", Map.of("name", key, "key", key, "type", "local", "tenantId", tenant,
                "enable", true, "orderNum", 1, "storageSourceAllParam", Map.of("filePath", files.toString(), "domain", "", "proxyPrivate", true, "proxyTokenTime", 1800)), globalToken).asInt();
    }

    private String code(String email) throws Exception {
        int before = SMTP.messageCount();
        JsonNode response = request(HttpMethod.POST, "/user/registration/email-code", Map.of("email", email), null);
        assertEquals("0", response.path("code").asText(), "SMTP code delivery endpoint must succeed");
        String code = SMTP.awaitCode(email.trim().toLowerCase(Locale.ROOT), before);
        assertFalse(response.toString().contains(code), "HTTP responses must not disclose verification codes");
        assertFalse(response.path("data").has("emailCode"));
        return code;
    }

    private static String email(String label) { return label + "." + NONCE + "@example.test"; }

    private JsonNode login(String identifier) {
        JsonNode value = ok(HttpMethod.POST, "/user/login", Map.of("username", identifier, "password", PASSWORD), null);
        assertFalse(value.path("token").asText().isBlank());
        assertFalse(value.has("password")); assertFalse(value.has("salt"));
        return value;
    }

    private void deniedLogin(String identifier) {
        denied(HttpMethod.POST, "/user/login", Map.of("username", identifier, "password", PASSWORD), null);
    }

    private JsonNode ok(HttpMethod method, String path, Object body, String token) {
        JsonNode response = request(method, path, body, token);
        assertEquals("0", response.path("code").asText(), method + " " + path + ": " + response);
        return response.path("data");
    }

    private void denied(HttpMethod method, String path, Object body, String token) {
        JsonNode response = request(method, path, body, token);
        assertNotEquals("0", response.path("code").asText(), method + " " + path + " must be denied");
        assertTrue(response.path("data").path("token").asText().isBlank());
    }

    private JsonNode request(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("axios-request", "true");
        headers.set("axios-from", "http://127.0.0.1:" + port);
        if (token != null) headers.set(TOKEN_HEADER, token);
        JsonNode response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
        assertNotNull(response, method + " " + path);
        return response;
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("student-cloud-registration-"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static String databaseName(String url) {
        if (url == null) return "";
        String address = url.split("\\?", 2)[0];
        return address.substring(address.lastIndexOf('/') + 1);
    }

    private record Account(int id, String accountId, String email, String role, int tenantId, String tenantName, String status) { }

    /** Minimal local SMTP sink: production JavaMail transport sends real MIME messages into memory. */
    private static final class LoopbackSmtp implements AutoCloseable {
        private final ServerSocket server;
        // Keep blocking test SMTP socket conversations independent of virtual-thread application work.
        private final ExecutorService connections = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());
        private final List<MimeMessage> messages = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread acceptor;

        private LoopbackSmtp() {
            try {
                server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                acceptor = new Thread(this::accept, "registration-test-smtp");
                acceptor.setDaemon(true);
                acceptor.start();
            } catch (IOException error) { throw new ExceptionInInitializerError(error); }
        }

        int port() { return server.getLocalPort(); }

        int messageCount() { synchronized (messages) { return messages.size(); } }

        void assertHealthy() { assertNull(failure.get(), "Loopback SMTP sink must remain healthy"); }

        String awaitCode(String recipient, int after) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (messages) {
                while (System.nanoTime() < deadline) {
                    assertHealthy();
                    for (int i = after; i < messages.size(); i++) {
                        MimeMessage message = messages.get(i);
                        Address[] recipients = message.getAllRecipients();
                        if (recipients != null && Arrays.stream(recipients).anyMatch(value -> recipient.equals(value.toString()))) {
                            assertEquals("mailer@example.test", message.getFrom()[0].toString());
                            var match = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(message.getContent().toString());
                            assertTrue(match.find(), "Delivered MIME text must contain a verification code");
                            return match.group();
                        }
                    }
                    messages.wait(50);
                }
            }
            throw new AssertionError("No verification mail arrived at the loopback SMTP sink");
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.submit(() -> conversation(socket));
                } catch (IOException error) {
                    if (!server.isClosed()) failure.compareAndSet(null, error);
                }
            }
        }

        private void conversation(Socket socket) {
            try (socket;
                 BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1))) {
                socket.setSoTimeout(10_000);
                reply(output, "220 localhost integration SMTP");
                String line;
                while ((line = input.readLine()) != null) {
                    String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith("EHLO")) {
                        reply(output, "250-localhost"); reply(output, "250 SIZE 100000");
                    } else if (command.startsWith("HELO") || command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) {
                        reply(output, "250 OK");
                    } else if (command.equals("DATA")) {
                        reply(output, "354 End with a single dot");
                        ByteArrayOutputStream mime = new ByteArrayOutputStream();
                        while ((line = input.readLine()) != null && !line.equals(".")) {
                            if (line.startsWith("..")) line = line.substring(1);
                            mime.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                        if (line == null) throw new EOFException("SMTP DATA ended before terminator");
                        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime.toByteArray()));
                        synchronized (messages) { messages.add(message); messages.notifyAll(); }
                        reply(output, "250 Accepted");
                    } else if (command.equals("QUIT")) {
                        reply(output, "221 Bye"); return;
                    } else reply(output, "502 Unsupported command");
                }
            } catch (Exception error) { failure.compareAndSet(null, error); }
        }

        private static void reply(BufferedWriter output, String text) throws IOException {
            output.write(text); output.write("\r\n"); output.flush();
        }

        @Override public void close() {
            try { server.close(); } catch (IOException ignored) { }
            connections.close();
            synchronized (messages) { messages.clear(); }
        }
    }
}
