package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantSecurityServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final TenantSecurityService security = new TenantSecurityService(users, storages);

    @Test
    void membersReceiveForbiddenRatherThanAnInvalidRequestError() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
            when(users.selectById(9)).thenReturn(user(UserRoleEnum.USER, 2));
            var exception = assertThrows(ForbiddenAccessException.class, security::currentManagedTenantId);
            assertEquals("30000", exception.getCode());
            assertThrows(ForbiddenAccessException.class, security::requireSystemAdmin);
            assertThrows(ForbiddenAccessException.class, () -> security.requireManageUser(10));
            assertThrows(ForbiddenAccessException.class, () -> security.requireManageStorage(10));
            verifyNoInteractions(storages);
            verify(users, never()).selectById(10);
        }
    }

    @Test
    void workspaceManagersRemainScopedAndCannotAdministerTheSystem() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
            when(users.selectById(9)).thenReturn(user(UserRoleEnum.TENANT_ADMIN, 2));
            assertEquals(2, security.currentManagedTenantId());
            assertThrows(ForbiddenAccessException.class, security::requireSystemAdmin);
        }
    }

    @Test
    void globalAdministratorsAreNotMistakenForWorkspaceManagers() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
            when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, 2));
            assertDoesNotThrow(security::requireSystemAdmin);
            assertThrows(ForbiddenAccessException.class, security::currentManagedTenantId);
        }
    }

    @Test
    void managerWithoutAWorkspaceIsForbidden() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
            when(users.selectById(9)).thenReturn(user(UserRoleEnum.TENANT_ADMIN, null));
            assertThrows(ForbiddenAccessException.class, security::currentManagedTenantId);
        }
    }

    @Test
    void subsequentRoleChecksReadTheCurrentDatabaseRole() {
        try (var auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
            when(users.selectById(9)).thenReturn(user(UserRoleEnum.ADMIN, 2), user(UserRoleEnum.USER, 2));
            assertDoesNotThrow(security::requireSystemAdmin);
            assertThrows(ForbiddenAccessException.class, security::requireSystemAdmin);
            verify(users, times(2)).selectById(9);
        }
    }

    private User user(UserRoleEnum role, Integer tenantId) {
        User user = new User();
        user.setId(9);
        user.setEnable(true);
        user.setRole(role);
        user.setTenantId(tenantId);
        return user;
    }
}
