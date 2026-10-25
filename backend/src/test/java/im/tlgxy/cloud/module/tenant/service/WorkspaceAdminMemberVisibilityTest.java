package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Executes the service's actual MyBatis WHERE and pagination against disposable SQLite rows. */
class WorkspaceAdminMemberVisibilityTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final WorkspaceJoinRequestMapper joins = mock(WorkspaceJoinRequestMapper.class);
    private SingleConnectionDataSource connection;
    private JdbcTemplate jdbc;
    private WorkspaceAdminService service;
    private record Query(String sql, Object[] values) { }

    @BeforeEach void database() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "workspace-real-member-query"), User.class);
        connection = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(connection);
        jdbc.execute("CREATE TABLE user(id INTEGER PRIMARY KEY, username TEXT, account_id TEXT, email TEXT, nickname TEXT, role TEXT, tenant_id INTEGER, enable BOOLEAN)");
        // Historical internal rows may use USER and belong to the same tenant: role alone is insufficient.
        insert(0, "template", "USER", 10, true);
        insert(1, "reserved-root", "USER", 10, true);
        insert(2, "retired-anonymous", "USER", 10, true);
        insert(3, "workspace-manager", "TENANT_ADMIN", 10, true);
        insert(99, "foreign-student", "USER", 20, true);
        Tenant tenant = new Tenant(); tenant.setId(10); tenant.setName("测试空间"); tenant.setEnable(true);
        when(security.currentManagedTenantId()).thenReturn(10);
        when(tenants.selectById(10)).thenReturn(tenant);
        when(joins.selectList(any())).thenReturn(List.of());
        when(users.selectCount(any())).thenAnswer(call -> {
            Query query = query(call.getArgument(0));
            return jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE " + query.sql(), Long.class, query.values());
        });
        when(users.selectList(any())).thenAnswer(call -> {
            Query query = query(call.getArgument(0));
            return jdbc.query("SELECT * FROM user WHERE " + query.sql(), (row, index) -> {
                User user = new User(); user.setId(row.getInt("id")); user.setUsername(row.getString("username"));
                user.setAccountId(row.getString("account_id")); user.setEmail(row.getString("email")); user.setNickname(row.getString("nickname"));
                user.setTenantId(row.getInt("tenant_id")); user.setRole(UserRoleEnum.valueOf(row.getString("role"))); user.setEnable(row.getBoolean("enable"));
                return user;
            }, query.values());
        });
        service = new WorkspaceAdminService(security, tenants, users, storages,
                mock(UserStorageSourceMapper.class), mock(UserManager.class), joins);
    }

    @AfterEach void close() { connection.destroy(); }

    @Test void countsAndPagesIncludeOnlyRealOrdinaryMembers() {
        IntStream.rangeClosed(101, 121).forEach(id -> insert(id, "student-" + id, "USER", 10, id % 2 == 1));
        var summary = service.summary();
        assertEquals(21, summary.memberCount());
        assertEquals(11, summary.enabledMemberCount());
        var first = service.members(1, "");
        var second = service.members(2, "");
        assertEquals(21, first.total()); assertEquals(20, first.items().size());
        assertEquals(21, second.total()); assertEquals(List.of(101), second.items().stream().map(WorkspaceAdminService.Member::id).toList());
        assertEquals(121, first.items().getFirst().id());
        assertTrue(first.items().stream().allMatch(member -> member.id() > 2));
        assertTrue(service.members(3, "").items().isEmpty());
    }

    @Test void searchCannotExposeInternalAccountsOrChangeItsTotalScope() {
        insert(101, "student", "USER", 10, true);
        for (String term : List.of("template", "retired-anonymous", "reserved-root", "SC0", "foreign")) {
            var page = service.members(1, term);
            assertEquals(0, page.total(), term);
            assertTrue(page.items().isEmpty(), term);
        }
        assertEquals(1, service.members(1, "student").total());
        assertEquals(101, service.members(1, "SC101").items().getFirst().id());
    }

    @Test void internalIdsAreRejectedForBothEnableStatesBeforeLoadingOrWritingThem() {
        for (int id : List.of(-1, 0, 1, 2)) {
            assertThrows(BizException.class, () -> service.setEnabled(id, true));
            assertThrows(BizException.class, () -> service.setEnabled(id, false));
        }
        verify(users, never()).selectById(any(java.io.Serializable.class));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(joins);
    }

    private void insert(int id, String username, String role, int tenantId, boolean enabled) {
        jdbc.update("INSERT INTO user VALUES(?,?,?,?,?,?,?,?)", id, username, "SC" + id, username + "@example.invalid", username, role, tenantId, enabled);
    }

    private Query query(LambdaQueryWrapper<User> wrapper) {
        String fragment = wrapper.getSqlSegment();
        var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.([A-Za-z0-9_]+)}").matcher(fragment);
        var values = new ArrayList<Object>();
        while (matcher.find()) {
            Object value = wrapper.getParamNameValuePairs().get(matcher.group(1));
            values.add(value instanceof UserRoleEnum role ? role.getValue() : value);
        }
        return new Query(matcher.replaceAll("?"), values.toArray());
    }
}
