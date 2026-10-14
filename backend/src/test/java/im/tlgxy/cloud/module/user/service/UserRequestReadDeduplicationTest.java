package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserRequestReadDeduplicationTest {

    private final UserMapper users = mock(UserMapper.class);
    private final WorkspaceJoinRequestMapper joins = mock(WorkspaceJoinRequestMapper.class);
    private final TenantService tenants = mock(TenantService.class);
    private final TenantSecurityService security = new TenantSecurityService(users, mock(StorageSourceMapper.class));
    private final UserService service = new UserService();
    private MockedStatic<TlgxyCloudAuthUtil> authentication;

    @BeforeAll
    static void initializeJoinMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "request-deduplication-test"),
                WorkspaceJoinRequest.class);
    }

    @BeforeEach
    void bindAuthenticatedReadRequest() {
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "workspaceJoinRequestMapper", joins);
        ReflectionTestUtils.setField(service, "tenantService", tenants);
        when(joins.selectCount(any())).thenReturn(0L);
        authentication = mockStatic(TlgxyCloudAuthUtil.class);
        authentication.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
        bindRequest("GET");
    }

    @AfterEach
    void releaseRequestAndAuthentication() {
        RequestContextHolder.resetRequestAttributes();
        authentication.close();
    }

    @Test
    void interceptorRoleAndBusinessChecksShareOnlyOneActorEntityRead() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, true));
        assertEquals(9, service.requireLoginUser(9).getId());
        assertTrue(service.isAdmin(9));
        assertDoesNotThrow(security::requireSystemAdmin);
        service.requireLoginUser(9);
        verify(users).selectById(9);
        // Membership is intentionally not cached, even inside the same request.
        verify(joins, times(2)).selectCount(any());
    }

    @Test
    void disablingTheAccountIsEffectiveOnTheNextRequestWithoutATtl() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, true), user(UserRoleEnum.ADMIN, false));
        assertDoesNotThrow(() -> service.requireLoginUser(9));
        assertTrue(service.isAdmin(9));
        bindRequest("GET");
        assertThrows(BizException.class, () -> service.requireLoginUser(9));
        assertFalse(service.isAdmin(9));
        verify(users, times(3)).selectById(9);
    }

    @Test
    void removingGlobalAdminRoleIsEffectiveOnTheNextRequest() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, true), user(UserRoleEnum.USER, true));
        assertTrue(service.isAdmin(9));
        assertDoesNotThrow(security::requireSystemAdmin);
        bindRequest("GET");
        assertFalse(service.isAdmin(9));
        assertThrows(ForbiddenAccessException.class, security::requireSystemAdmin);
        verify(users, times(2)).selectById(9);
    }

    @Test
    void failedMembershipChecksDiscardTheEntityAndAlwaysRevalidate() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.USER, true));
        when(joins.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.requireLoginUser(9));
        assertThrows(BizException.class, () -> service.requireLoginUser(9));
        verify(users, times(2)).selectById(9);
        verify(joins, times(2)).selectCount(any());
        verify(tenants, times(2)).getEnabledById(2);
    }

    @Test
    void workspaceStateIsCheckedEveryTimeAndARejectionDropsTheEntity() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.USER, true));
        when(tenants.getEnabledById(2)).thenThrow(new BizException("工作空间已停用"));
        assertThrows(BizException.class, () -> service.requireActiveUser(9));
        assertThrows(BizException.class, () -> service.requireActiveUser(9));
        verify(users, times(2)).selectById(9);
        verify(tenants, times(2)).getEnabledById(2);
        verifyNoInteractions(joins);
    }

    @Test
    void targetUserAdministrationDoesNotReadFromTheActorSnapshot() {
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, true));
        User target = user(UserRoleEnum.USER, true);
        target.setId(10);
        when(users.selectById(10)).thenReturn(target);
        assertSame(target, security.requireManageUser(10));
        assertSame(target, security.requireManageUser(10));
        verify(users).selectById(9);
        verify(users, times(2)).selectById(10);
    }

    @Test
    void writeRequestAuthorizationNeverUsesAReadSnapshot() {
        bindRequest("POST");
        when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, true));
        service.requireLoginUser(9);
        assertTrue(service.isAdmin(9));
        security.requireSystemAdmin();
        verify(users, times(3)).selectById(9);
    }

    private void bindRequest(String method) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, "/admin/storages")));
    }

    private User user(UserRoleEnum role, boolean enabled) {
        User user = new User();
        user.setId(9);
        user.setRole(role);
        user.setEnable(enabled);
        user.setTenantId(2);
        return user;
    }
}
