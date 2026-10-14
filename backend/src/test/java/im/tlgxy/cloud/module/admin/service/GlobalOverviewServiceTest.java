package im.tlgxy.cloud.module.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.cache.overview.GlobalOverviewCache;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.mapper.AgentTaskMapper;
import im.tlgxy.cloud.module.ai.model.automation.AgentTask;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.share.mapper.ShareLinkMapper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalOverviewServiceTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final ShareLinkMapper shares = mock(ShareLinkMapper.class);
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final SystemConfigService config = mock(SystemConfigService.class);
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private GlobalOverviewService service;
    private SystemConfigDTO settings;

    @BeforeAll
    static void mappings() {
        var mapping = new MapperBuilderAssistant(new MybatisConfiguration(), "overview-test");
        TableInfoHelper.initTableInfo(mapping, User.class);
        TableInfoHelper.initTableInfo(mapping, AgentTask.class);
    }

    @BeforeEach
    void database() throws Exception {
        dataSource = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = spy(new JdbcTemplate(dataSource));
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY, name TEXT, enable BOOLEAN, owner_user_id INTEGER)");
        jdbc.execute("CREATE TABLE user(id INTEGER PRIMARY KEY, username TEXT, account_id TEXT, email TEXT, password TEXT, role TEXT, tenant_id INTEGER, enable BOOLEAN)");
        jdbc.execute("CREATE TABLE workspace_join_request(id INTEGER PRIMARY KEY, user_id INTEGER UNIQUE, tenant_id INTEGER, status TEXT)");
        jdbc.execute("CREATE TABLE storage_source(id INTEGER PRIMARY KEY, tenant_id INTEGER, enable BOOLEAN)");
        jdbc.execute("CREATE TABLE share_link(id INTEGER PRIMARY KEY, expire_date DATETIME)");
        jdbc.execute("CREATE TABLE ai_task(id TEXT PRIMARY KEY, status TEXT, expires_at BIGINT, title TEXT, steps_json TEXT, results_json TEXT)");
        jdbc.execute("CREATE TABLE ai_schedule(id TEXT PRIMARY KEY, enabled BOOLEAN, last_error TEXT, title TEXT)");
        jdbc.execute("CREATE TABLE login_log(id INTEGER PRIMARY KEY, username TEXT, create_time DATETIME, result TEXT, password TEXT, ip TEXT, user_agent TEXT, referer TEXT)");
        settings = new SystemConfigDTO();
        settings.setLoginLogMode(LoginLogModeEnum.OFF);
        when(config.getSystemConfig()).thenReturn(settings);
        when(users.selectCount(any())).thenReturn(12L, 3L);
        when(tenants.selectCount(any())).thenReturn(4L);
        when(storages.selectCount(any())).thenReturn(8L);
        when(shares.selectCount(any())).thenReturn(6L);
        when(tasks.selectCount(any())).thenReturn(2L);
        service = new GlobalOverviewService(security, users, tenants, storages, shares, tasks, jdbc, config);
        clearInvocations(jdbc);
    }

    @AfterEach
    void closeDatabase() { dataSource.destroy(); }

    @Test
    void denialHappensBeforeEveryDataSourceAndConfigurationRead() {
        doThrow(new BizException("denied")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, service::overview);
        assertThrows(BizException.class, () -> service.workspaces("x", true, 0, 0));
        verify(security, times(2)).requireSystemAdmin();
        verifyNoInteractions(jdbc, users, tenants, storages, shares, tasks, config);
    }

    @Test
    void invalidPagingAndOversizedSearchNeverReachDatabase() {
        for (int[] pair : new int[][] {{0, 10}, {-1, 10}, {10_001, 10}, {Integer.MAX_VALUE, 10},
                {1, 0}, {1, -1}, {1, 101}, {1, Integer.MAX_VALUE}}) {
            assertThrows(BizException.class, () -> service.workspaces(null, null, pair[0], pair[1]));
        }
        assertThrows(BizException.class, () -> service.workspaces("x".repeat(101), null, 1, 10));
        verify(security, times(9)).requireSystemAdmin();
        verifyNoInteractions(jdbc, users, tenants, storages, shares, tasks, config);
    }

    @Test
    void workspacePagingSortsFiltersAndProjectsOnlySafeAccountId() throws Exception {
        jdbc.update("INSERT INTO tenant VALUES(10, 'Alpha', 1, 101), (20, 'Beta', 0, 201), (30, 'Gamma', 1, NULL)");
        jdbc.update("INSERT INTO user VALUES(101, 'private-admin-name', 'SCOWNER101', 'private@example.invalid', 'secret-password', 'TENANT_ADMIN', 10, 1),"
                + "(102, 'member', 'SC102', NULL, NULL, 'USER', 10, 0), (103, 'pending', 'SC103', NULL, NULL, 'USER', 10, 0)");
        jdbc.update("INSERT INTO workspace_join_request VALUES(1, 103, 10, 'PENDING')");
        jdbc.update("INSERT INTO storage_source VALUES(1, 10, 1), (2, 10, 0)");
        clearInvocations(jdbc);

        var first = service.workspaces(null, null, 1, 2);
        assertEquals(3, first.total());
        assertEquals(2, first.records().size());
        assertEquals(30, first.records().getFirst().id());
        assertNull(first.records().getFirst().ownerAccountId());
        var second = service.workspaces(null, null, 2, 2);
        var alpha = second.records().getFirst();
        assertEquals(10, alpha.id());
        assertEquals(3, alpha.members());
        assertEquals(1, alpha.workspaceAdmins());
        assertEquals(1, alpha.pendingMembers());
        assertEquals(2, alpha.storageSources());
        assertEquals("SCOWNER101", alpha.ownerAccountId());
        assertEquals(1, service.workspaces("  10 ", true, 1, 100).total());
        assertEquals(1, service.workspaces(null, null, 1, 10, 10).total());
        assertEquals(10, service.workspaces(null, null, 1, 10, 10).records().getFirst().id());
        assertEquals(0, service.workspaces(null, null, 1, 10, 1).total());
        assertThrows(BizException.class, () -> service.workspaces(null, null, 1, 10, 0));
        assertEquals(1, service.workspaces(null, false, 1, 100).total());
        assertTrue(service.workspaces(null, null, 10_000, 100).records().isEmpty());

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(alpha));
        assertEquals(Set.of("id", "name", "enabled", "members", "storageSources", "workspaceAdmins", "pendingMembers", "ownerAccountId"),
                iterableFields(json));
        String serialized = json.toString();
        assertFalse(serialized.contains("private-admin-name"));
        assertFalse(serialized.contains("private@example.invalid"));
        assertFalse(serialized.contains("secret-password"));
        verifyNoInteractions(users, tenants, storages, shares, tasks, config);
    }

    @Test
    void wildcardAndInjectionStringsAreLiteralBoundValues() {
        jdbc.update("INSERT INTO tenant VALUES(10, 'team_%!', 1, NULL), (20, 'team-other', 1, NULL),"
                + "(30, '雪 学生空间', 1, NULL)");
        assertEquals(1, service.workspaces("_%!", null, 1, 10).total());
        assertEquals("team_%!", service.workspaces("team_%!", null, 1, 10).records().getFirst().name());
        assertEquals(0, service.workspaces("' OR 1=1 --", null, 1, 10).total());
        assertEquals(1, service.workspaces("雪", null, 1, 10).total());
        assertEquals(3, service.workspaces("  ", null, 1, 10).total());
    }

    @Test
    void statusesSeparateApprovalFromEnabledAndKeepOriginalTotals() {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO tenant VALUES(10, 'Active', 1, 101), (20, 'Disabled', 0, 201)");
        jdbc.update("INSERT INTO user(id,role,tenant_id,enable) VALUES"
                + "(1,'ADMIN',NULL,1),(2,'GUEST',10,1),(101,'TENANT_ADMIN',10,1),"
                + "(102,'USER',10,1),(103,'USER',10,0),(104,'USER',10,0),"
                + "(105,'USER',10,1),(106,'USER',10,0),(107,'USER',10,1)");
        jdbc.update("INSERT INTO workspace_join_request VALUES"
                + "(1,102,10,'APPROVED'),(2,103,10,'APPROVED'),(3,104,10,'PENDING'),"
                + "(4,105,10,'PENDING'),(5,106,10,'REJECTED'),(6,107,10,'REJECTED')");
        jdbc.update("INSERT INTO storage_source VALUES(1,10,1),(2,10,0)");
        jdbc.update("INSERT INTO share_link VALUES(1,?), (2,NULL), (3,?)", new Timestamp(now - 5_000), new Timestamp(now + 86_400_000));
        jdbc.update("INSERT INTO ai_task(id,status,title,steps_json,results_json) VALUES"
                + "('one','RUNNING','private title','secret steps','secret result'),('two','FAILED',NULL,NULL,NULL),"
                + "('three','PARTIAL',NULL,NULL,NULL),('four','INTERRUPTED',NULL,NULL,NULL),('five','SUCCEEDED',NULL,NULL,NULL)");
        jdbc.update("INSERT INTO ai_schedule VALUES('one',1,NULL,'private schedule'), ('two',1,'private error','private'), ('three',0,'   ',NULL)");

        var overview = service.overview();
        assertEquals(4, overview.workspaces());
        assertEquals(12, overview.members());
        assertEquals(3, overview.workspaceAdmins());
        assertEquals(8, overview.storageSources());
        assertEquals(6, overview.shares());
        assertEquals(2, overview.pendingAgentTasks());
        var status = overview.statuses();
        assertEquals(1, status.enabledWorkspaces());
        assertEquals(1, status.disabledWorkspaces());
        assertEquals(2, status.activeMembers());
        assertEquals(1, status.disabledMembers());
        assertEquals(2, status.pendingMemberships());
        assertEquals(1, status.enabledStorages());
        assertEquals(1, status.expiredShares());
        assertEquals(1, status.runningAgentTasks());
        assertEquals(3, status.failedAgentTasks());
        assertEquals(2, status.enabledSchedules());
        assertEquals(1, status.failedSchedules());
        assertEquals("SQLite", overview.runtime().databaseType());
        assertTrue(overview.runtime().uptimeMs() >= 0);
        assertTrue(overview.runtime().usedHeapBytes() > 0);
        assertTrue(overview.runtime().databaseLatencyMs() >= 0);
        assertFalse(overview.loginLoggingEnabled());
        settings.setLoginLogMode(LoginLogModeEnum.IGNORE_ALL_PWD);
        assertTrue(service.overview().loginLoggingEnabled());
    }

    @Test
    void loginCountersFillSevenDaysExcludeFutureAndReturnOnlySafeRecentProjection() throws Exception {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long old = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli() + 1_000;
        jdbc.update("INSERT INTO login_log VALUES(1,'account',?,'登录成功','secret-password','private-ip','private-agent','private-ref')", new Timestamp(now - 2_000));
        jdbc.update("INSERT INTO login_log VALUES(2,'account',?,'raw private exception',NULL,NULL,NULL,NULL)", new Timestamp(now - 1_000));
        jdbc.update("INSERT INTO login_log VALUES(3,'old',?,'登录成功',NULL,NULL,NULL,NULL)", new Timestamp(old));
        jdbc.update("INSERT INTO login_log VALUES(4,'future',?,'登录成功',NULL,NULL,NULL,NULL)", new Timestamp(now + 3_600_000));

        var overview = service.overview();
        assertEquals(1, overview.loginSummary().successes24h());
        assertEquals(1, overview.loginSummary().failures24h());
        assertEquals(7, overview.dailyLogins().size());
        assertEquals(today.minusDays(6).toString(), overview.dailyLogins().getFirst().date());
        assertEquals(1, overview.dailyLogins().getFirst().successes());
        assertEquals(0, overview.dailyLogins().get(1).successes());
        assertEquals(0, overview.dailyLogins().get(1).failures());
        assertEquals(today.toString(), overview.dailyLogins().getLast().date());
        assertEquals(1, overview.dailyLogins().getLast().successes());
        assertEquals(1, overview.dailyLogins().getLast().failures());
        assertEquals(zone.getId(), overview.timeZone());
        assertTrue(overview.generatedAt() >= now);
        assertEquals(4, overview.recentLogins().getFirst().id());
        assertTrue(overview.recentLogins().getFirst().success());
        assertFalse(overview.recentLogins().get(1).success());
        assertEquals(now - 1_000, overview.recentLogins().get(1).createdAt());
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(overview);
        assertFalse(json.contains("secret-password"));
        assertFalse(json.contains("private-ip"));
        assertFalse(json.contains("private-agent"));
        assertFalse(json.contains("private-ref"));
        assertFalse(json.contains("raw private exception"));
        assertEquals(Set.of("id", "username", "createdAt", "success"),
                iterableFields(mapper.valueToTree(overview.recentLogins().getFirst())));
    }

    @Test
    void emptyDatabaseHasZeroFilledDailyCountersAndBoundedRecentList() {
        var overview = service.overview();
        assertEquals(0, overview.loginSummary().successes24h());
        assertEquals(0, overview.loginSummary().failures24h());
        assertTrue(overview.dailyLogins().stream().allMatch(day -> day.successes() == 0 && day.failures() == 0));
        assertTrue(overview.recentLogins().isEmpty());
        for (int index = 1; index <= 20; index++) jdbc.update(
                "INSERT INTO login_log(id,username,create_time,result) VALUES(?,?,?,?)", index, "account" + index,
                new Timestamp(System.currentTimeMillis() - 1000), index % 2 == 0 ? "登录成功" : null);
        var recent = service.overview().recentLogins();
        assertEquals(8, recent.size());
        assertEquals(20, recent.getFirst().id());
    }

    @Test
    void databaseProductNeverLeaksAnUnknownDriverStringOrUrl() {
        assertEquals("MySQL", GlobalOverviewService.safeDatabaseProduct("MySQL"));
        assertEquals("SQLite", GlobalOverviewService.safeDatabaseProduct("SQLite"));
        assertEquals("Other", GlobalOverviewService.safeDatabaseProduct("jdbc:mysql://root:password@private-db"));
        assertEquals("Other", GlobalOverviewService.safeDatabaseProduct(null));
    }

    @Test
    void hotDashboardReusesQueriesButRechecksAuthorizationAndSamplesRuntimeEveryTime() {
        service = new GlobalOverviewService(security, users, tenants, storages, shares, tasks, jdbc, config,
                new GlobalOverviewCache(Duration.ofSeconds(10), null, "test:"));
        var first = service.overview();
        var second = service.overview();
        assertEquals(first.generatedAt(), second.generatedAt());
        assertNotSame(first.runtime(), second.runtime());
        verify(security, times(2)).requireSystemAdmin();
        verify(tenants, times(1)).selectCount(any());
        verify(users, times(2)).selectCount(any());
        verify(storages, times(1)).selectCount(any());
        verify(shares, times(1)).selectCount(any());
        verify(tasks, times(1)).selectCount(any());
        clearInvocations(jdbc, users, tenants, storages, shares, tasks, config);
        doThrow(new BizException("demoted")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, service::overview);
        verifyNoInteractions(jdbc, users, tenants, storages, shares, tasks, config);
    }

    private Set<String> iterableFields(com.fasterxml.jackson.databind.JsonNode json) {
        var fields = new java.util.HashSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
