package im.tlgxy.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.cache.overview.GlobalOverviewCache;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/Flyway console regression. All accounts, files and private markers are synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobalOverviewWorkflowTest {
    private static final Path WORK = temporaryDirectory();
    private static final String MYSQL_URL = System.getenv("CLOUD_OVERVIEW_TEST_MYSQL_URL");
    private static final String ROOT_ACCOUNT = "Superadmin-console";
    private static final String PASSWORD = "Console-password-123"; // Isolated fixture only; never an application credential.
    private static final String TOKEN_HEADER = "tongligong-student-cloud-token";
    private static final String WORKSPACE_A = "Console A_%! synthetic";
    private static final String WORKSPACE_B = "Console B synthetic";
    private static final String PRIVATE_MARKER = "CONSOLE_PRIVATE_FIXTURE";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiRateLimiter rateLimit;
    @Autowired GlobalOverviewCache overviewCache;
    @Autowired SystemConfigService systemConfig;
    @LocalServerPort int port;

    private String globalToken;
    private String managerToken;
    private String memberToken;
    private int workspaceA;
    private int workspaceB;
    private int managerA;
    private int managerB;
    private int memberA;
    private int pendingA;
    private String managerAccountId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        boolean mysql = MYSQL_URL != null && !MYSQL_URL.isBlank();
        if (mysql && (!MYSQL_URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3306/student_cloud_role_test_[a-z0-9_]+(?:\\?.*)?")
                || databaseName(MYSQL_URL).equals(databaseName(System.getenv("CLOUD_TEST_MYSQL_URL")))
                || databaseName(MYSQL_URL).equals(databaseName(System.getenv("CLOUD_REGISTRATION_TEST_MYSQL_URL"))))) {
            throw new IllegalArgumentException("Overview tests require a distinct dedicated local student_cloud_role_test_* database");
        }
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        properties.add("spring.datasource.url", () -> mysql ? MYSQL_URL : "jdbc:sqlite:" + WORK.resolve("overview.db"));
        properties.add("spring.datasource.username", () -> mysql ? System.getenv("CLOUD_OVERVIEW_TEST_MYSQL_USER") : "");
        properties.add("spring.datasource.password", () -> mysql ? System.getenv("CLOUD_OVERVIEW_TEST_MYSQL_PASSWORD") : "");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        properties.add("tongligong-student-cloud.log.path", () -> WORK.resolve("logs").toString());
        properties.add("tongligong-student-cloud.workspace-storage.root", () -> WORK.resolve("shared-workspaces").toString());
        properties.add("tongligong-student-cloud.ai.enabled", () -> "false");
        properties.add("tongligong-student-cloud.cache.overview-ttl", () -> "1s");
        // Override any local QQ configuration. This suite sends no email and contacts no AI provider.
        properties.add("tongligong-student-cloud.registration.mail.enabled", () -> "false");
        properties.add("tongligong-student-cloud.registration.mail.host", () -> "127.0.0.1");
        properties.add("tongligong-student-cloud.registration.mail.username", () -> "");
        properties.add("tongligong-student-cloud.registration.mail.password", () -> "");
        properties.add("tongligong-student-cloud.registration.mail.from", () -> "");
    }

    @BeforeEach
    void isolateRateLimitWindows() {
        Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(rateLimit, "windows");
        assertNotNull(cache);
        cache.clear();
        // Fixture writes bypass production services; do not carry a previous scenario's summary forward.
        ReflectionTestUtils.setField(overviewCache, "statistics", null);
        ReflectionTestUtils.setField(overviewCache, "details", null);
    }

    @Test
    @Order(1)
    void installAndCreateAnIndependentConsoleFixture() throws Exception {
        assertFalse(ok(HttpMethod.GET, "/api/install/status", null, null).asBoolean());
        ok(HttpMethod.POST, "/api/install", Map.of("siteName", "Superadmin console test-only", "username", ROOT_ACCOUNT,
                "password", PASSWORD), null);
        logging(LoginLogModeEnum.OFF);
        globalToken = login(ROOT_ACCOUNT);
        assertEquals("ADMIN", ok(HttpMethod.GET, "/user/login/check", null, globalToken).path("role").asText());

        workspaceA = workspace(WORKSPACE_A);
        workspaceB = workspace(WORKSPACE_B);
        managerA = user("console-manager-a", "TENANT_ADMIN", workspaceA, true);
        managerB = user("console-manager-b", "TENANT_ADMIN", workspaceB, true);
        memberA = user("console-member-a", "USER", workspaceA, true);
        user("console-disabled-a", "USER", workspaceA, false);
        pendingA = user("console-pending-a", "USER", workspaceA, false);
        int rejected = user("console-rejected-a", "USER", workspaceA, false);
        int approvedDisabled = user("console-approved-disabled-a", "USER", workspaceA, false);
        int pendingButEnabled = user("console-pending-enabled-a", "USER", workspaceA, true);
        jdbc.update("UPDATE tenant SET owner_user_id=? WHERE id=?", managerA, workspaceA);
        jdbc.update("UPDATE tenant SET owner_user_id=? WHERE id=?", managerB, workspaceB);
        jdbc.update("UPDATE `user` SET email=? WHERE id=?", "console-private@example.invalid", managerA);
        managerAccountId = jdbc.queryForObject("SELECT account_id FROM `user` WHERE id=?", String.class, managerA);
        joinRequest(memberA, "APPROVED");
        joinRequest(pendingA, "PENDING");
        joinRequest(rejected, "REJECTED");
        joinRequest(approvedDisabled, "APPROVED");
        // Deliberately inconsistent disposable fixture: enable cannot turn a pending application into a member.
        joinRequest(pendingButEnabled, "PENDING");

        storage("console-a", workspaceA, true);
        storage("console-a-disabled", workspaceA, false);
        storage("console-b", workspaceB, true);
        seedAgentAndShareCounts();
        managerToken = login("console-manager-a");
        memberToken = login("console-member-a");
        assertNotNull(managerAccountId);
        assertFalse(managerAccountId.isBlank());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM login_log", Integer.class));
    }

    @Test
    @Order(2)
    void realOverviewCountsApprovalAndTaskStatesWithoutDisclosingPrivateDataOrWriting() {
        Map<String, Long> before = rowCounts();
        JsonNode overview = ok(HttpMethod.GET, "/admin/overview", null, globalToken);
        assertEquals(3, overview.path("workspaces").asInt());
        assertEquals(8, overview.path("members").asInt());
        assertEquals(2, overview.path("workspaceAdmins").asInt());
        assertEquals(5, overview.path("storageSources").asInt());
        assertEquals(3, overview.path("shares").asInt());
        assertEquals(1, overview.path("pendingAgentTasks").asInt(), "Expired PENDING tasks are not actionable");
        JsonNode status = overview.path("statuses");
        assertEquals(3, status.path("enabledWorkspaces").asInt());
        assertEquals(0, status.path("disabledWorkspaces").asInt());
        assertEquals(3, status.path("activeMembers").asInt());
        assertEquals(2, status.path("disabledMembers").asInt(), "Pending/rejected users must not inflate disabled accounts");
        assertEquals(2, status.path("pendingMemberships").asInt());
        assertEquals(4, status.path("enabledStorages").asInt());
        assertEquals(1, status.path("expiredShares").asInt());
        assertEquals(1, status.path("runningAgentTasks").asInt());
        assertEquals(3, status.path("failedAgentTasks").asInt());
        assertEquals(2, status.path("enabledSchedules").asInt());
        assertEquals(1, status.path("failedSchedules").asInt());
        assertFalse(overview.path("loginLoggingEnabled").asBoolean());
        assertEquals(7, overview.path("dailyLogins").size());
        for (JsonNode day : overview.path("dailyLogins")) {
            assertEquals(0, day.path("successes").asInt());
            assertEquals(0, day.path("failures").asInt());
        }
        assertTrue(overview.path("generatedAt").asLong() > 0);
        assertDoesNotThrow(() -> ZoneId.of(overview.path("timeZone").asText()));
        JsonNode runtime = overview.path("runtime");
        assertEquals(MYSQL_URL == null || MYSQL_URL.isBlank() ? "SQLite" : "MySQL", runtime.path("databaseType").asText());
        assertTrue(runtime.path("uptimeMs").asLong() >= 0);
        assertTrue(runtime.path("usedHeapBytes").asLong() > 0);
        assertTrue(runtime.path("maxHeapBytes").asLong() > 0);
        assertTrue(runtime.path("databaseLatencyMs").asLong() >= 0);
        assertFalse(runtime.toString().contains("jdbc:"));
        assertEquals(Set.of("uptimeMs", "usedHeapBytes", "maxHeapBytes", "databaseType", "databaseLatencyMs"), fields(runtime));
        assertNoPrivateOverviewFields(overview);
        assertEquals(before, rowCounts());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ai_task WHERE id='console-pending'", String.class));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_task WHERE id='console-running'", String.class));
    }

    @Test
    @Order(3)
    void workspacePageHasStableSortCorrectOneToManyCountsAndLiteralNameSearch() {
        JsonNode defaultPage = ok(HttpMethod.GET, "/admin/overview/workspaces", null, globalToken);
        assertEquals(3, defaultPage.path("total").asInt());
        assertEquals(1, defaultPage.path("page").asInt());
        assertEquals(10, defaultPage.path("pageSize").asInt());
        assertEquals(workspaceB, defaultPage.path("records").get(0).path("id").asInt());
        assertEquals(workspaceA, defaultPage.path("records").get(1).path("id").asInt());
        JsonNode second = get(query("/admin/overview/workspaces", "page", 2, "pageSize", 1), globalToken);
        JsonNode alpha = second.path("records").get(0);
        assertEquals(workspaceA, alpha.path("id").asInt());
        assertEquals(7, alpha.path("members").asInt());
        assertEquals(3, alpha.path("storageSources").asInt());
        assertEquals(1, alpha.path("workspaceAdmins").asInt());
        assertEquals(2, alpha.path("pendingMembers").asInt());
        assertEquals(managerAccountId, alpha.path("ownerAccountId").asText());
        assertEquals(Set.of("id", "name", "enabled", "members", "storageSources", "workspaceAdmins", "pendingMembers", "ownerAccountId"), fields(alpha));
        assertFalse(alpha.toString().contains("console-manager-a"));
        assertFalse(alpha.toString().contains("console-private@example.invalid"));

        for (String literal : List.of("_%!", "A_%!", "  " + WORKSPACE_A + "  ")) {
            JsonNode matches = get(query("/admin/overview/workspaces", "keyword", literal), globalToken);
            assertEquals(1, matches.path("total").asInt(), "Literal search: " + literal);
            assertEquals(workspaceA, matches.path("records").get(0).path("id").asInt());
        }
        assertEquals(1, get(query("/admin/overview/workspaces", "keyword", String.valueOf(workspaceB)), globalToken).path("total").asInt());
        JsonNode target = get(query("/admin/overview/workspaces", "workspaceId", workspaceA), globalToken);
        assertEquals(1, target.path("total").asInt());
        assertEquals(workspaceA, target.path("records").get(0).path("id").asInt());
        assertEquals(0, get(query("/admin/overview/workspaces", "keyword", "' OR 1=1 --"), globalToken).path("total").asInt());
        assertEquals(3, get(query("/admin/overview/workspaces", "keyword", "  "), globalToken).path("total").asInt());
        assertEquals(0, get(query("/admin/overview/workspaces", "page", 10_000, "pageSize", 100), globalToken).path("records").size());
        tenantEnabled(workspaceB, WORKSPACE_B, false);
        try {
            assertEquals(1, get(query("/admin/overview/workspaces", "enabled", false), globalToken).path("total").asInt());
            assertEquals(workspaceB, get(query("/admin/overview/workspaces", "enabled", false), globalToken).path("records").get(0).path("id").asInt());
            assertEquals(2, get(query("/admin/overview/workspaces", "enabled", true), globalToken).path("total").asInt());
            assertEquals(1, ok(HttpMethod.GET, "/admin/overview", null, globalToken).path("statuses").path("disabledWorkspaces").asInt());
        } finally { tenantEnabled(workspaceB, WORKSPACE_B, true); }
    }

    @Test
    @Order(4)
    void invalidPagingBooleanAndSearchParametersAreClientErrorsNotServerFailures() {
        for (Object[] parameters : List.of(new Object[] {"page", 0}, new Object[] {"page", -1},
                new Object[] {"page", 10_001}, new Object[] {"page", "not-a-number"},
                new Object[] {"pageSize", 0}, new Object[] {"pageSize", -1}, new Object[] {"pageSize", 101},
                new Object[] {"pageSize", "999999999999999999999"},
                new Object[] {"enabled", "not-a-boolean"}, new Object[] {"keyword", "x".repeat(101)},
                new Object[] {"workspaceId", 0}, new Object[] {"workspaceId", "wrong-id"})) {
            denied(HttpMethod.GET, query("/admin/overview/workspaces", parameters), null, globalToken);
        }
        denied(HttpMethod.POST, URI.create(base() + "/admin/tenant"),
                Map.of("name", "x".repeat(81), "enable", true), globalToken);
        String boundaryName = "x".repeat(80);
        tenantEnabled(workspaceB, boundaryName, true);
        try {
            JsonNode boundary = get(query("/admin/overview/workspaces", "keyword", boundaryName), globalToken);
            assertEquals(1, boundary.path("total").asInt());
            assertEquals(workspaceB, boundary.path("records").get(0).path("id").asInt());
        } finally { tenantEnabled(workspaceB, WORKSPACE_B, true); }
        assertEquals(3, ok(HttpMethod.GET, "/admin/overview/workspaces", null, globalToken).path("total").asInt());
    }

    @Test
    @Order(5)
    void rolesAndFreshAccountWorkspaceStateProtectAlreadyEstablishedSessions() {
        for (String token : Arrays.asList(managerToken, memberToken, null)) {
            for (String path : List.of("/admin/overview", "/admin/overview/workspaces", "/admin/login/log/list")) {
                denied(HttpMethod.GET, URI.create(base() + path), null, token);
            }
            denied(HttpMethod.GET, query("/admin/overview/workspaces", "page", 0), null, token);
            denied(HttpMethod.GET, query("/admin/login/log/list", "orderBy", "not_a_column"), null, token);
        }
        ok(HttpMethod.POST, "/admin/user/disable/" + memberA, null, globalToken);
        try {
            denied(HttpMethod.GET, URI.create(base() + "/user/login/check"), null, memberToken);
            denied(HttpMethod.POST, URI.create(base() + "/user/login"),
                    Map.of("username", "console-member-a", "password", PASSWORD), null);
            assertEquals(3, ok(HttpMethod.GET, "/admin/overview", null, globalToken).path("statuses").path("disabledMembers").asInt());
        } finally { ok(HttpMethod.POST, "/admin/user/enable/" + memberA, null, globalToken); }

        tenantEnabled(workspaceA, WORKSPACE_A, false);
        try {
            denied(HttpMethod.GET, URI.create(base() + "/api/workspace-admin/summary"), null, managerToken);
            denied(HttpMethod.GET, URI.create(base() + "/user/login/check"), null, managerToken);
            denied(HttpMethod.GET, URI.create(base() + "/user/login/check"), null, memberToken);
            denied(HttpMethod.POST, URI.create(base() + "/user/login"),
                    Map.of("username", "console-manager-a", "password", PASSWORD), null);
        } finally { tenantEnabled(workspaceA, WORKSPACE_A, true); }
        managerToken = login("console-manager-a");
        memberToken = login("console-member-a");
        assertEquals(workspaceA, ok(HttpMethod.GET, "/api/workspace-admin/summary", null, managerToken).path("tenantId").asInt());
        assertTrue(ok(HttpMethod.GET, "/user/login/check", null, memberToken).path("isLogin").asBoolean());
        denied(HttpMethod.POST, URI.create(base() + "/user/login"),
                Map.of("username", "console-pending-enabled-a", "password", PASSWORD), null);
    }

    @Test
    @Order(6)
    void loginHistoryUsesSafeProjectionsZeroFilledDaysAndValidatedLogQueries() throws InterruptedException {
        logging(LoginLogModeEnum.OFF);
        // This database belongs exclusively to this suite; replace only its synthetic login history.
        jdbc.update("DELETE FROM login_log");
        long now = System.currentTimeMillis() / 1000 * 1000;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        seedLogin(1001, "console-recent-1", now - 600_000, "登录成功");
        seedLogin(1002, "console-recent-2", now - 540_000, "登录成功");
        seedLogin(1003, "console-recent-failure", now - 480_000, PRIVATE_MARKER + " raw exception");
        seedLogin(1004, "console-recent-null", now - 420_000, null);
        seedLogin(1005, "console-five-days", noon(today.minusDays(5), zone), "登录成功");
        seedLogin(1006, "console-four-days", noon(today.minusDays(4), zone), PRIVATE_MARKER + " old exception");
        seedLogin(1007, "console-two-days", noon(today.minusDays(2), zone), "登录成功");
        seedLogin(1008, "console-outside-week", noon(today.minusDays(10), zone), "登录成功");
        seedLogin(1009, "console-future", now + 86_400_000, "登录成功");

        JsonNode overview = ok(HttpMethod.GET, "/admin/overview", null, globalToken);
        assertFalse(overview.path("loginLoggingEnabled").asBoolean(), "Historical counts do not imply logging is enabled");
        assertEquals(2, overview.path("loginSummary").path("successes24h").asInt());
        assertEquals(2, overview.path("loginSummary").path("failures24h").asInt());
        assertEquals(7, overview.path("dailyLogins").size());
        Map<String, JsonNode> days = new HashMap<>();
        for (JsonNode day : overview.path("dailyLogins")) days.put(day.path("date").asText(), day);
        LocalDate responseToday = Instant.ofEpochMilli(overview.path("generatedAt").asLong())
                .atZone(ZoneId.of(overview.path("timeZone").asText())).toLocalDate();
        assertEquals(responseToday.minusDays(6).toString(), overview.path("dailyLogins").get(0).path("date").asText());
        assertEquals(responseToday.toString(), overview.path("dailyLogins").get(6).path("date").asText());
        assertEquals(1, days.get(today.minusDays(5).toString()).path("successes").asInt());
        assertEquals(0, days.get(today.minusDays(3).toString()).path("successes").asInt());
        assertEquals(0, days.get(today.minusDays(3).toString()).path("failures").asInt());
        assertEquals(1, days.get(today.minusDays(4).toString()).path("failures").asInt());
        assertEquals(1, days.get(today.minusDays(2).toString()).path("successes").asInt());
        assertEquals(4, sum(overview.path("dailyLogins"), "successes"));
        assertEquals(3, sum(overview.path("dailyLogins"), "failures"));
        JsonNode recent = overview.path("recentLogins");
        assertEquals(8, recent.size());
        assertEquals(1009, recent.get(0).path("id").asInt());
        assertEquals(now + 86_400_000, recent.get(0).path("createdAt").asLong());
        assertTrue(recent.get(0).path("success").asBoolean());
        assertFalse(recent.get(1).path("success").asBoolean());
        for (JsonNode row : recent) assertEquals(Set.of("id", "username", "createdAt", "success"), fields(row));
        assertNoPrivateOverviewFields(overview);

        JsonNode logPage = get(query("/admin/login/log/list", "page", 1, "limit", 3, "orderBy", "id", "orderDirection", "desc"), globalToken);
        assertEquals(3, logPage.size());
        assertEquals(1009, logPage.get(0).path("id").asInt());
        for (JsonNode log : logPage) {
            assertFalse(log.has("password"));
            assertFalse(log.has("salt"));
            assertFalse(log.has("email"));
            assertEquals(Set.of("id", "username", "createTime", "ip", "userAgent", "referer", "result"), fields(log));
        }
        assertFalse(logPage.toString().contains(PRIVATE_MARKER + " password"));
        assertEquals(9, get(query("/admin/login/log/list", "searchDate", ""), globalToken).size());
        assertEquals(9, get(query("/admin/login/log/list", "searchDate", "", "searchDate", ""), globalToken).size());
        assertEquals(9, get(query("/admin/login/log/list", "orderBy", "createTime"), globalToken).size());
        for (Object[] parameters : List.of(new Object[] {"orderBy", "create_time desc; DROP TABLE user"},
                new Object[] {"orderBy", "password"}, new Object[] {"orderDirection", "DESC; SELECT 1"},
                new Object[] {"limit", 100_001}, new Object[] {"page", 0},
                new Object[] {"searchDate", "2026-10-03 00:00:00", "searchDate", "2026-10-01 00:00:00"})) {
            denied(HttpMethod.GET, query("/admin/login/log/list", parameters), null, globalToken);
        }
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM login_log", Integer.class));

        logging(LoginLogModeEnum.IGNORE_ALL_PWD);
        try {
            login(ROOT_ACCOUNT);
            denied(HttpMethod.POST, URI.create(base() + "/user/login"),
                    Map.of("username", ROOT_ACCOUNT, "password", "wrong-test-only-password"), null);
            Thread.sleep(1100); // Login statistics are a TTL snapshot, not an uncached per-request counter.
            JsonNode recorded = ok(HttpMethod.GET, "/admin/overview", null, globalToken);
            assertTrue(recorded.path("loginLoggingEnabled").asBoolean());
            assertEquals(3, recorded.path("loginSummary").path("successes24h").asInt());
            assertEquals(3, recorded.path("loginSummary").path("failures24h").asInt());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM login_log WHERE username=? AND `password`='******'", Integer.class, ROOT_ACCOUNT));
        } finally { logging(LoginLogModeEnum.OFF); }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM login_log", Long.class);
        login(ROOT_ACCOUNT);
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM login_log", Long.class));
        assertFalse(ok(HttpMethod.GET, "/admin/overview", null, globalToken).path("loginLoggingEnabled").asBoolean());
    }

    @Test
    @Order(7)
    void workspaceMemberPagesExcludeTemplatesSortManagersFirstAndKeepFiltersAndProjectionsSafe() {
        String path = "/admin/overview/workspaces/" + workspaceA + "/users";
        Map<String, Long> before = rowCounts();
        JsonNode page = ok(HttpMethod.GET, path, null, globalToken);
        assertEquals(7, page.path("total").asInt());
        assertEquals(1, page.path("page").asInt()); assertEquals(10, page.path("pageSize").asInt());
        assertEquals(managerA, page.path("records").get(0).path("id").asInt(), "Managers precede newer ordinary members");
        assertEquals("TENANT_ADMIN", page.path("records").get(0).path("role").asText());
        assertEquals(1, get(query(path, "role", "TENANT_ADMIN"), globalToken).path("total").asInt());
        JsonNode members = get(query(path, "role", "USER", "pageSize", 2), globalToken);
        assertEquals(6, members.path("total").asInt()); assertEquals(2, members.path("records").size());
        assertTrue(members.path("records").get(0).path("id").asInt() > members.path("records").get(1).path("id").asInt());
        assertEquals(0, get(query("/admin/overview/workspaces/1/users"), globalToken).path("total").asInt());
        assertEquals(1, get(query("/admin/overview/workspaces/" + workspaceB + "/users"), globalToken).path("total").asInt());
        for (JsonNode row : page.path("records")) {
            assertEquals(Set.of("id", "accountId", "username", "nickname", "email", "role", "tenantId",
                    "tenantName", "enable", "membershipStatus", "createTime"), fields(row));
            assertTrue(row.path("id").asInt() > 2); assertEquals(workspaceA, row.path("tenantId").asInt());
            assertEquals(WORKSPACE_A, row.path("tenantName").asText());
            assertTrue(row.path("createTime").isNull() || row.path("createTime").asLong() > 0);
            assertFalse(row.has("password")); assertFalse(row.has("salt")); assertFalse(row.has("userStorageSourceList"));
            if (row.path("id").asInt() == memberA) {
                assertEquals("ACTIVE", row.path("membershipStatus").asText()); assertTrue(row.path("enable").asBoolean());
            }
            if (row.path("id").asInt() == pendingA) {
                assertEquals("PENDING", row.path("membershipStatus").asText()); assertFalse(row.path("enable").asBoolean());
            }
            if ("console-rejected-a".equals(row.path("username").asText())) assertEquals("REJECTED", row.path("membershipStatus").asText());
        }
        assertEquals(1, get(query(path, "keyword", managerAccountId), globalToken).path("total").asInt());
        assertEquals(1, get(query(path, "keyword", "console-private@example.invalid"), globalToken).path("total").asInt());
        jdbc.update("UPDATE `user` SET nickname=? WHERE id=?", "literal_%! synthetic", memberA);
        try {
            JsonNode literal = get(query(path, "keyword", "  _%!  "), globalToken);
            assertEquals(1, literal.path("total").asInt()); assertEquals(memberA, literal.path("records").get(0).path("id").asInt());
        } finally { jdbc.update("UPDATE `user` SET nickname=? WHERE id=?", "console-member-a", memberA); }
        assertEquals(0, get(query(path, "keyword", "' OR 1=1 --"), globalToken).path("total").asInt());
        assertEquals(0, get(query(path, "page", 10_000, "pageSize", 100), globalToken).path("records").size());
        for (Object[] parameters : List.of(new Object[] {"role", "ADMIN"}, new Object[] {"role", "GUEST"},
                new Object[] {"role", "USER' OR 1=1"}, new Object[] {"page", 0}, new Object[] {"page", 10_001},
                new Object[] {"pageSize", 0}, new Object[] {"pageSize", 101}, new Object[] {"keyword", "x".repeat(101)}))
            denied(HttpMethod.GET, query(path, parameters), null, globalToken);
        denied(HttpMethod.GET, query("/admin/overview/workspaces/0/users"), null, globalToken);
        denied(HttpMethod.GET, query("/admin/overview/workspaces/999999/users"), null, globalToken);
        for (String token : Arrays.asList(managerToken, memberToken, null))
            denied(HttpMethod.GET, query(path), null, token);
        assertEquals(before, rowCounts());
    }

    @Test
    @Order(8)
    void legacyWorkspaceStorageRepairIsGlobalOnlyIsolatedAndIdempotentWithoutRestoringRevocations() throws IOException {
        String automaticPath = "/admin/overview/workspaces/" + workspaceA + "/storage";
        long sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM storage_source", Long.class);
        long grantCount = jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source", Long.class);
        JsonNode automatic = ok(HttpMethod.POST, automaticPath, null, globalToken);
        assertFalse(automatic.path("created").asBoolean());
        assertEquals(sourceCount, jdbc.queryForObject("SELECT COUNT(*) FROM storage_source", Long.class));
        assertEquals(grantCount, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source", Long.class));
        for (String token : Arrays.asList(managerToken, memberToken, null))
            denied(HttpMethod.POST, query(automaticPath), null, token);

        // A deliberately pre-feature workspace, only inside this suite's dedicated database.
        jdbc.update("INSERT INTO tenant(name,enable) VALUES('Console legacy shared-drive fixture',1)");
        int legacy = jdbc.queryForObject("SELECT id FROM tenant WHERE name='Console legacy shared-drive fixture'", Integer.class);
        int manager = user("console-legacy-manager", "TENANT_ADMIN", legacy, true);
        int member = ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", "console-legacy-member",
                "nickname", "Read only synthetic member", "password", PASSWORD, "role", "USER", "tenantId", legacy,
                "enable", true, "defaultPermissions", List.of("preview", "download")), globalToken).path("id").asInt();
        int pending = user("console-legacy-pending", "USER", legacy, true);
        jdbc.update("INSERT INTO workspace_join_request(tenant_id,user_id,status,created_at) VALUES(?,?,'PENDING',?)", legacy, pending, System.currentTimeMillis());
        jdbc.update("UPDATE tenant SET owner_user_id=? WHERE id=?", manager, legacy);
        String path = "/admin/overview/workspaces/" + legacy + "/storage";
        Path legacyFiles = WORK.resolve("shared-workspaces/tenant-" + legacy);
        Files.createDirectories(legacyFiles);
        Path unknown = legacyFiles.resolve("unknown-before-provision.txt");
        Files.writeString(unknown, "Synthetic unknown-directory collision");
        denied(HttpMethod.POST, query(path), null, globalToken);
        assertEquals("Synthetic unknown-directory collision", Files.readString(unknown));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM storage_source WHERE `key`=?", Integer.class, "workspace-" + legacy));
        Files.delete(unknown); // Only a file this test just created inside its own temporary directory.

        JsonNode provisioned = ok(HttpMethod.POST, path, null, globalToken);
        int storageId = provisioned.path("storageId").asInt();
        assertTrue(provisioned.path("created").asBoolean());
        assertEquals(Set.of("storageId", "storageKey", "tenantId", "name", "created"), fields(provisioned));
        assertFalse(provisioned.toString().contains(WORK.toString()));
        assertEquals("workspace-" + legacy, provisioned.path("storageKey").asText());
        assertEquals(legacyFiles.toString(), jdbc.queryForObject("SELECT value FROM storage_source_config WHERE storage_id=? AND name='filePath'", String.class, storageId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=?", Integer.class, storageId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=? AND user_id=?", Integer.class, storageId, manager));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=? AND user_id=?", Integer.class, storageId, member));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=? AND user_id IN (1,2,?,?)", Integer.class, storageId, managerA, pending));
        assertEquals("TENANT_ADMIN", jdbc.queryForObject("SELECT role FROM `user` WHERE id=?", String.class, manager));
        String managerSession = login("console-legacy-manager");
        String readOnlySession = login("console-legacy-member");
        ok(HttpMethod.POST, "/api/file/operator/mkdir", Map.of("storageKey", "workspace-" + legacy, "path", "/", "name", "manager-files"), managerSession);
        assertTrue(Files.isDirectory(legacyFiles.resolve("manager-files")));
        denied(HttpMethod.POST, query("/api/file/operator/mkdir"), Map.of("storageKey", "workspace-" + legacy, "path", "/", "name", "not-allowed"), readOnlySession);
        denied(HttpMethod.POST, query("/api/storage/files"), Map.of("storageKey", "workspace-" + legacy, "path", "/"), managerToken);
        denied(HttpMethod.POST, query("/api/storage/files"), Map.of("storageKey", "workspace-" + workspaceB, "path", "/"), managerSession);
        denied(HttpMethod.POST, query("/api/storage/files"), Map.of("storageKey", "workspace-" + legacy, "path", "/"), null);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=?", Long.class, storageId);
        assertFalse(ok(HttpMethod.POST, path, null, globalToken).path("created").asBoolean());
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=?", Long.class, storageId));

        // Explicitly remove the manager's access using the real global authorization editor.
        ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("id", manager, "username", "console-legacy-manager",
                "nickname", "Revoked manager", "enable", true, "role", "TENANT_ADMIN", "tenantId", legacy,
                "defaultPermissions", List.of("upload"), "userStorageSourceList", List.of()), globalToken);
        assertFalse(ok(HttpMethod.POST, path, null, globalToken).path("created").asBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_storage_source WHERE storage_source_id=? AND user_id=?", Integer.class, storageId, manager));
        denied(HttpMethod.POST, query("/api/storage/files"), Map.of("storageKey", "workspace-" + legacy, "path", "/"), managerSession);
        tenantEnabled(legacy, "Console legacy shared-drive fixture", false);
        try { denied(HttpMethod.POST, query(path), null, globalToken); }
        finally { tenantEnabled(legacy, "Console legacy shared-drive fixture", true); }
        denied(HttpMethod.POST, query("/admin/overview/workspaces/0/storage"), null, globalToken);
    }

    private int workspace(String name) {
        return ok(HttpMethod.POST, "/admin/tenant", Map.of("name", name, "enable", true), globalToken).path("id").asInt();
    }

    private void tenantEnabled(int id, String name, boolean enabled) {
        ok(HttpMethod.POST, "/admin/tenant", Map.of("id", id, "name", name, "enable", enabled), globalToken);
    }

    private int user(String username, String role, int tenantId, boolean enabled) {
        return ok(HttpMethod.POST, "/admin/user/saveOrUpdate", Map.of("username", username, "nickname", username,
                "password", PASSWORD, "role", role, "tenantId", tenantId, "enable", enabled), globalToken).path("id").asInt();
    }

    private void joinRequest(int userId, String status) {
        jdbc.update("INSERT INTO workspace_join_request(tenant_id,user_id,status,created_at) VALUES(?,?,?,?)",
                workspaceA, userId, status, System.currentTimeMillis());
    }

    private void storage(String key, int tenantId, boolean enabled) throws IOException {
        Path directory = Files.createDirectory(WORK.resolve(key));
        Files.writeString(directory.resolve("synthetic-marker.txt"), "Synthetic console fixture; no user files.");
        ok(HttpMethod.POST, "/admin/storage", Map.of("name", key, "key", key, "type", "local", "tenantId", tenantId,
                "enable", enabled, "orderNum", 1, "storageSourceAllParam", Map.of("filePath", directory.toString(),
                        "domain", "", "proxyPrivate", true, "proxyTokenTime", 1800)), globalToken);
    }

    private void seedAgentAndShareCounts() {
        long now = System.currentTimeMillis();
        List<String> statuses = List.of("PENDING", "PENDING", "RUNNING", "FAILED", "PARTIAL", "INTERRUPTED", "SUCCEEDED", "CANCELLED");
        List<String> ids = List.of("console-pending", "console-expired", "console-running", "console-failed", "console-partial", "console-interrupted", "console-succeeded", "console-cancelled");
        for (int index = 0; index < ids.size(); index++) jdbc.update("""
                INSERT INTO ai_task(id,owner_user_id,title,status,steps_json,results_json,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, ids.get(index), managerA, PRIVATE_MARKER + " title", statuses.get(index),
                "[{\"private\":\"" + PRIVATE_MARKER + " steps\"}]", "[{\"private\":\"" + PRIVATE_MARKER + " results\"}]",
                now, index == 1 ? now - 86_400_000 : now + 7 * 86_400_000L);
        for (int index = 0; index < 3; index++) jdbc.update("""
                INSERT INTO ai_schedule(id,owner_user_id,title,task_type,storage_key,directory_path,
                  day_of_week,hour_of_day,minute_of_hour,enabled,next_run_at,last_error)
                VALUES(?,?,?,'ORGANIZE_FILES','console-a','/',1,12,0,?,?,?)
                """, "console-schedule-" + index, managerA, PRIVATE_MARKER + " schedule", index < 2,
                now + 365 * 86_400_000L, index == 1 ? PRIVATE_MARKER + " schedule error" : index == 2 ? "   " : null);
        for (int index = 0; index < 3; index++) jdbc.update("""
                INSERT INTO share_link(share_key,password,expire_date,storage_key,share_path,share_item,
                  create_date,share_type,user_id,download_count,access_count)
                VALUES(?,?,?,?,?,?,?,'FILE',?,0,0)
                """, "console-share-" + index, "secret12",
                index == 0 ? new Timestamp(now - 86_400_000) : index == 1 ? new Timestamp(now + 7 * 86_400_000L) : null,
                "console-a", "/", "[]", new Timestamp(now), managerA);
    }

    private void seedLogin(int id, String username, long createdAt, String result) {
        jdbc.update("""
                INSERT INTO login_log(id,username,create_time,result,password,ip,user_agent,referer)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, username, new Timestamp(createdAt), result, PRIVATE_MARKER + " password", "203.0.113.10",
                PRIVATE_MARKER + " agent", PRIVATE_MARKER + " referer");
    }

    private void logging(LoginLogModeEnum mode) {
        var settings = systemConfig.getSystemConfig();
        settings.setLoginLogMode(mode);
        systemConfig.updateSystemConfig(settings);
    }

    private String login(String username) {
        String token = ok(HttpMethod.POST, "/user/login", Map.of("username", username, "password", PASSWORD), null).path("token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("tenant", "user", "workspace_join_request", "storage_source", "share_link", "ai_task", "ai_schedule"))
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class));
        return counts;
    }

    private void assertNoPrivateOverviewFields(JsonNode overview) {
        String encoded = overview.toString();
        for (String forbidden : List.of(PRIVATE_MARKER, PASSWORD, "console-private@example.invalid", "secret12", "203.0.113.10",
                "\"password\"", "\"salt\"", "\"email\"", "\"steps\"", "\"results\"", "\"stepsJson\"", "\"resultsJson\"",
                "\"ip\"", "\"userAgent\"", "\"referer\"", "\"result\"")) assertFalse(encoded.contains(forbidden), "Private field leaked: " + forbidden);
    }

    private int sum(JsonNode records, String field) {
        int sum = 0;
        for (JsonNode record : records) sum += record.path(field).asInt();
        return sum;
    }

    private Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private JsonNode get(URI uri, String token) {
        JsonNode response = request(HttpMethod.GET, uri, null, token).getBody();
        assertNotNull(response);
        assertEquals("0", response.path("code").asText(), uri.getPath() + ": " + response);
        return response.path("data");
    }

    private JsonNode ok(HttpMethod method, String path, Object body, String token) {
        JsonNode response = request(method, URI.create(base() + path), body, token).getBody();
        assertNotNull(response);
        assertEquals("0", response.path("code").asText(), method + " " + path + ": " + response);
        return response.path("data");
    }

    private void denied(HttpMethod method, URI uri, Object body, String token) {
        ResponseEntity<JsonNode> response = request(method, uri, body, token);
        assertFalse(response.getStatusCode().is5xxServerError(), "Invalid/unauthorized request must not be a server failure: " + uri);
        assertNotNull(response.getBody());
        assertNotEquals("0", response.getBody().path("code").asText(), "Request must be rejected: " + uri);
        assertTrue(response.getBody().path("data").path("token").asText().isBlank());
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, URI uri, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("axios-request", "true");
        headers.set("axios-from", base());
        if (token != null) headers.set(TOKEN_HEADER, token);
        return http.exchange(uri, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private URI query(String path, Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Query requires name/value pairs");
        var builder = UriComponentsBuilder.fromUriString(base()).path(path);
        for (int index = 0; index < pairs.length; index += 2) builder.queryParam(pairs[index].toString(), pairs[index + 1]);
        return builder.build().encode().toUri();
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private static long noon(LocalDate date, ZoneId zone) { return date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(); }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("student-cloud-overview-"); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    private static String databaseName(String url) {
        if (url == null) return "";
        String address = url.split("\\?", 2)[0];
        return address.substring(address.lastIndexOf('/') + 1);
    }
}
