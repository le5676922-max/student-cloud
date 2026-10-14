package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceAdminMembershipStatusTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final WorkspaceJoinRequestMapper requests = mock(WorkspaceJoinRequestMapper.class);
    private WorkspaceAdminService service;

    @BeforeAll
    static void initializeMappings() {
        MapperBuilderAssistant mapping = new MapperBuilderAssistant(new MybatisConfiguration(), "member-status-test");
        TableInfoHelper.initTableInfo(mapping, User.class);
        TableInfoHelper.initTableInfo(mapping, WorkspaceJoinRequest.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkspaceAdminService(security, tenants, users, mock(StorageSourceMapper.class),
                mock(UserStorageSourceMapper.class), mock(UserManager.class), requests);
        Tenant tenant = new Tenant();
        tenant.setId(10);
        tenant.setEnable(true);
        when(security.currentManagedTenantId()).thenReturn(10);
        when(tenants.selectById(10)).thenReturn(tenant);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void oneTenantScopedBatchAddsApplicationStatusToTwentyMembers() {
        List<User> pageMembers = IntStream.rangeClosed(101, 120).mapToObj(id -> member(id, id > 103)).toList();
        when(users.selectCount(any())).thenReturn(40L);
        when(users.selectList(any())).thenReturn(pageMembers);
        when(requests.selectList(any())).thenReturn(List.of(
                application(101, "PENDING"), application(102, "REJECTED"), application(103, "APPROVED")));

        var result = service.members(2, "");

        assertEquals(40, result.total());
        assertEquals(2, result.page());
        assertEquals(20, result.items().size());
        assertEquals("PENDING", result.items().get(0).membershipStatus());
        assertEquals("REJECTED", result.items().get(1).membershipStatus());
        assertEquals("APPROVED", result.items().get(2).membershipStatus());
        assertFalse(result.items().get(2).enabled(), "Approved members may still be intentionally disabled");
        assertEquals("ACTIVE", result.items().get(3).membershipStatus());
        assertTrue(result.items().get(3).enabled());
        assertEquals(pageMembers.getFirst().getAccountId(), result.items().getFirst().accountId());

        ArgumentCaptor<LambdaQueryWrapper> batch = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(requests, times(1)).selectList(batch.capture());
        String sql = batch.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id ="));
        assertTrue(sql.contains("user_id IN"));
        var expectedIdsAndTenant = new HashSet<Integer>(IntStream.rangeClosed(101, 120).boxed().toList());
        expectedIdsAndTenant.add(10);
        assertEquals(expectedIdsAndTenant, new HashSet<>(batch.getValue().getParamNameValuePairs().values()));
        assertEquals(21, batch.getValue().getParamNameValuePairs().size(), "Only this page's IDs and tenant are queried");
        verifyNoMoreInteractions(requests);

        ArgumentCaptor<LambdaQueryWrapper> pageQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users).selectList(pageQuery.capture());
        // MyBatis quotes the reserved role column; quoting is not a missing scope predicate.
        String memberSql = pageQuery.getValue().getSqlSegment().replace("`", "");
        assertTrue(memberSql.contains("tenant_id ="));
        assertTrue(memberSql.contains("role ="));
        assertTrue(memberSql.contains("LIMIT 20 OFFSET 20"));
    }

    @Test
    void emptyPageDoesNotQueryApplications() {
        when(users.selectCount(any())).thenReturn(0L);
        when(users.selectList(any())).thenReturn(List.of());

        var result = service.members(1, null);

        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
        verifyNoInteractions(requests);
    }

    @Test
    void enabledResponseKeepsApprovedApplicationStatus() {
        when(users.selectById(103)).thenReturn(member(103, false));
        when(requests.selectCount(any())).thenReturn(0L);
        when(requests.selectList(any())).thenReturn(List.of(application(103, "APPROVED")));

        var result = service.setEnabled(103, true);

        assertTrue(result.enabled());
        assertEquals("APPROVED", result.membershipStatus());
    }

    private User member(int id, boolean enabled) {
        User member = new User();
        member.setId(id);
        member.setUsername("member-" + id);
        member.setAccountId("SC" + id);
        member.setTenantId(10);
        member.setRole(UserRoleEnum.USER);
        member.setEnable(enabled);
        return member;
    }

    private WorkspaceJoinRequest application(int userId, String status) {
        WorkspaceJoinRequest request = new WorkspaceJoinRequest();
        request.setTenantId(10);
        request.setUserId(userId);
        request.setStatus(status);
        return request;
    }
}
