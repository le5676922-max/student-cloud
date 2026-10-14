package im.tlgxy.cloud.module.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.mapper.AgentTaskMapper;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.share.mapper.ShareLinkMapper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalWorkspaceMemberServiceTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final ShareLinkMapper shares = mock(ShareLinkMapper.class);
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final SystemConfigService config = mock(SystemConfigService.class);
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private GlobalOverviewService service;

    @BeforeEach
    void database() throws Exception {
        source = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = spy(new JdbcTemplate(source));
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY, name TEXT, enable BOOLEAN)");
        jdbc.execute("""
                CREATE TABLE user(id INTEGER PRIMARY KEY, account_id TEXT, username TEXT, nickname TEXT,
                  email TEXT, role TEXT, tenant_id INTEGER, enable BOOLEAN, create_time DATETIME,
                  password TEXT, salt TEXT, default_permissions TEXT)
                """);
        jdbc.execute("CREATE TABLE workspace_join_request(id INTEGER PRIMARY KEY, user_id INTEGER UNIQUE, tenant_id INTEGER, status TEXT)");
        jdbc.update("INSERT INTO tenant VALUES(10,'Workspace A',1),(20,'Workspace B',0)");
        service = new GlobalOverviewService(security, users, tenants, storages, shares, tasks, jdbc, config);
        clearInvocations(jdbc);
    }

    @AfterEach
    void close() { source.destroy(); }

    @Test
    void permissionDenialPrecedesParameterValidationAndEveryQuery() {
        doThrow(new BizException("denied")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> service.workspaceMembers(0, "ADMIN", "x".repeat(101), 0, 0));
        verify(security).requireSystemAdmin();
        verifyNoInteractions(jdbc, users, tenants, storages, shares, tasks, config);
    }

    @Test
    void invalidIdsPagingRolesAndSearchNeverReachDatabase() {
        assertThrows(BizException.class, () -> service.workspaceMembers(0, null, null, 1, 10));
        assertThrows(BizException.class, () -> service.workspaceMembers(-1, null, null, 1, 10));
        for (String role : List.of("ADMIN", "GUEST", "SUPER_ADMIN", "", "UNKNOWN", "USER' OR 1=1 --"))
            assertThrows(BizException.class, () -> service.workspaceMembers(10, role, null, 1, 10));
        for (int[] paging : new int[][] {{0,10}, {10_001,10}, {1,0}, {1,101}, {Integer.MAX_VALUE,10}})
            assertThrows(BizException.class, () -> service.workspaceMembers(10, null, null, paging[0], paging[1]));
        assertThrows(BizException.class, () -> service.workspaceMembers(10, null, "x".repeat(101), 1, 10));
        verifyNoInteractions(jdbc, users, tenants, storages, shares, tasks, config);
    }

    @Test
    void exactWorkspaceScopeExcludesBuiltInsAndSortsAdministratorsBeforeNewerMembers() {
        insert(1, "global", "ADMIN", 10, true);
        insert(2, "guest", "GUEST", 10, true);
        insert(101, "manager-one", "TENANT_ADMIN", 10, true);
        insert(102, "manager-two", "TENANT_ADMIN", 10, true);
        insert(999, "newest-member", "USER", 10, true);
        insert(998, "member-two", "USER", 10, false);
        insert(1000, "foreign-manager", "TENANT_ADMIN", 20, true);

        var first = service.workspaceMembers(10, null, null, 1, 2);
        assertEquals(4, first.total());
        assertEquals(List.of(102,101), first.records().stream().map(GlobalOverviewService.WorkspaceMember::id).toList());
        assertTrue(first.records().stream().allMatch(user -> user.tenantId() == 10 && "Workspace A".equals(user.tenantName())));
        var second = service.workspaceMembers(10, null, null, 2, 2);
        assertEquals(List.of(999,998), second.records().stream().map(GlobalOverviewService.WorkspaceMember::id).toList());
        assertEquals(2, service.workspaceMembers(10, "TENANT_ADMIN", null, 1, 100).total());
        assertEquals(2, service.workspaceMembers(10, "USER", null, 1, 100).total());
        assertEquals(1, service.workspaceMembers(20, "TENANT_ADMIN", null, 1, 100).total(), "Global administrators can inspect disabled workspaces");
        assertTrue(service.workspaceMembers(10, null, null, 10_000, 100).records().isEmpty());
        verifyNoInteractions(users, tenants, storages, shares, tasks, config);
    }

    @Test
    void safeProjectionSeparatesMembershipFromEnableAndDoesNotLoadStorageOrCredentials() throws Exception {
        insert(101, "active", "USER", 10, true);
        insert(102, "approved-disabled", "USER", 10, false);
        insert(103, "pending", "USER", 10, false);
        insert(104, "rejected", "USER", 10, false);
        jdbc.update("INSERT INTO workspace_join_request VALUES(1,102,10,'APPROVED'),(2,103,10,'PENDING'),(3,104,10,'REJECTED')");
        var records = service.workspaceMembers(10, "USER", null, 1, 10).records();
        assertEquals(List.of("REJECTED", "PENDING", "ACTIVE", "ACTIVE"), records.stream()
                .map(GlobalOverviewService.WorkspaceMember::membershipStatus).toList());
        assertFalse(records.get(2).enable(), "An approved account can remain intentionally disabled");
        assertEquals(1_700_000_000_000L, records.getFirst().createTime());
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(records);
        assertFalse(json.contains("secret-password"));
        assertFalse(json.contains("secret-salt"));
        assertFalse(json.contains("secret-permissions"));
        assertFalse(json.contains("userStorageSourceList"));
        var fields = new java.util.HashSet<String>();
        mapper.valueToTree(records.getFirst()).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "accountId", "username", "nickname", "email", "role", "tenantId", "tenantName",
                "enable", "membershipStatus", "createTime"), fields);
        verifyNoInteractions(users, tenants, storages, shares, tasks, config);
    }

    @Test
    void parameterizedKeywordMatchesLiteralWildcardsAndAllFourIdentityFields() {
        insert(101, "name_%!", "TENANT_ADMIN", 10, true);
        insert(102, "name_other", "USER", 10, true);
        insert(201, "name_%!", "USER", 20, true);
        assertEquals(1, service.workspaceMembers(10, null, "_%!", 1, 10).total());
        assertEquals(101, service.workspaceMembers(10, null, "name_%!", 1, 10).records().getFirst().id());
        assertEquals(0, service.workspaceMembers(10, null, "' OR 1=1 --", 1, 10).total());
        assertEquals(1, service.workspaceMembers(10, null, "  SC101  ", 1, 10).total());
        assertEquals(1, service.workspaceMembers(10, null, "nick-101", 1, 10).total());
        assertEquals(1, service.workspaceMembers(10, null, "user-101@example.invalid", 1, 10).total());
        assertEquals(2, service.workspaceMembers(10, null, "  ", 1, 10).total());
    }

    @Test
    void emptyWorkspaceIsDistinctFromAnUnknownWorkspace() {
        assertEquals(0, service.workspaceMembers(10, null, null, 1, 10).total());
        assertTrue(service.workspaceMembers(10, null, null, 1, 10).records().isEmpty());
        assertThrows(BizException.class, () -> service.workspaceMembers(9999, null, null, 1, 10));
    }

    private void insert(int id, String username, String role, int tenant, boolean enabled) {
        jdbc.update("INSERT INTO user VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", id, "SC" + id, username, "nick-" + id,
                "user-" + id + "@example.invalid", role, tenant, enabled, new Timestamp(1_700_000_000_000L),
                "secret-password", "secret-salt", "secret-permissions");
    }
}
