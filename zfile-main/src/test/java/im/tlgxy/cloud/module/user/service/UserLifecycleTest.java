package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.login.verify.impl.PasswordVerifyService;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLifecycleTest {
    @Test
    void disabledAdministratorHasNoAdminRoleAndCannotUseExistingSession() {
        UserMapper mapper = mock(UserMapper.class);
        User user = user(1, UserRoleEnum.ADMIN, false);
        when(mapper.selectById(3)).thenReturn(user);
        UserService service = service(mapper, mock(TenantService.class));
        assertFalse(service.isAdmin(3));
        assertThrows(BizException.class, () -> service.requireActiveUser(3));
    }

    @Test
    void stoppedWorkspaceBlocksActiveAccount() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(3)).thenReturn(user(3, UserRoleEnum.USER, true));
        TenantService tenants = mock(TenantService.class);
        when(tenants.getEnabledById(1)).thenThrow(new BizException("工作空间已停用"));
        UserService service = service(mapper, tenants);
        assertThrows(BizException.class, () -> service.requireActiveUser(3));
    }

    @Test
    void validPasswordStillRequiresActiveAccount() {
        UserService users = mock(UserService.class);
        User user = user(3, UserRoleEnum.USER, false);
        var encrypted = PasswordVerifyUtils.encrypt("Test-password-123");
        user.setPassword(encrypted.getFirst());
        user.setSalt(encrypted.getSecond());
        when(users.getByUsername("student")).thenReturn(user);
        when(users.requireLoginUser(3)).thenThrow(new BizException("账号已停用"));
        PasswordVerifyService verifier = new PasswordVerifyService();
        ReflectionTestUtils.setField(verifier, "userService", users);
        UserLoginRequest request = new UserLoginRequest();
        request.setUsername("student");
        request.setPassword("Test-password-123");
        assertThrows(BizException.class, () -> verifier.verify(request));
        verify(users).requireLoginUser(3);
    }

    @Test
    void mainAdministratorCannotBeDisabledOrDemotedThroughSave() {
        UserMapper mapper = mock(UserMapper.class);
        UserService service = service(mapper, mock(TenantService.class));
        TenantSecurityService security = mock(TenantSecurityService.class);
        User admin = user(1, UserRoleEnum.ADMIN, true);
        when(security.currentUser()).thenReturn(admin);
        when(security.requireManageUser(1)).thenReturn(admin);
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        SaveUserRequest request = new SaveUserRequest();
        request.setId(1);
        request.setEnable(false);
        assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        request.setEnable(true);
        request.setRole(UserRoleEnum.USER);
        assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        verifyNoInteractions(mapper);
    }

    @Test
    void guestsAndPermissionTemplatesCannotLogInEvenWhenEnabled() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(2)).thenReturn(user(2, UserRoleEnum.GUEST, true));
        when(mapper.selectById(0)).thenReturn(user(0, UserRoleEnum.USER, true));
        UserService service = service(mapper, mock(TenantService.class));
        assertFalse(service.isAdmin(2));
        assertThrows(BizException.class, () -> service.requireLoginUser(2));
        assertThrows(BizException.class, () -> service.requireLoginUser(0));
    }

    @Test
    void ssoRegistrationAlwaysCreatesAnOrdinaryUserFromEnabledTemplate() {
        UserMapper mapper = mock(UserMapper.class);
        User template = user(0, UserRoleEnum.USER, true);
        template.setDefaultPermissions(java.util.Set.of("download"));
        when(mapper.selectById(0)).thenReturn(template);
        doAnswer(invocation -> {
            User created = invocation.getArgument(0);
            assertEquals(UserRoleEnum.USER, created.getRole());
            assertEquals(1, created.getTenantId());
            assertEquals(java.util.Set.of("download"), created.getDefaultPermissions());
            assertNull(created.getPassword());
            created.setId(12);
            return 1;
        }).when(mapper).insert(any(User.class));
        UserService service = service(mapper, mock(TenantService.class));
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", events);
        assertEquals(12, service.registerFromTemplate("sso-student"));
        verify(events).publishEvent(any(im.tlgxy.cloud.module.user.event.UserCopyEvent.class));
        template.setEnable(false);
        assertThrows(BizException.class, () -> service.registerFromTemplate("blocked-sso-student"));
    }

    private UserService service(UserMapper mapper, TenantService tenants) {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantService", tenants);
        return service;
    }

    private User user(int id, UserRoleEnum role, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setTenantId(1);
        user.setEnable(enabled);
        return user;
    }
}
