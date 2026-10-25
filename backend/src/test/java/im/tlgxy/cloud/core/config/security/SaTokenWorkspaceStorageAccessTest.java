package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaTokenWorkspaceStorageAccessTest {
    @Test void onlyExactStorageManagementMethodsAreDelegatedToTenantAdministrators() {
        assertTrue(SaTokenConfigure.isWorkspaceStorageEndpoint("GET","/admin/storages"));
        assertTrue(SaTokenConfigure.isWorkspaceStorageEndpoint("POST","/admin/storage"));
        assertTrue(SaTokenConfigure.isWorkspaceStorageEndpoint("GET","/admin/storage/42"));
        assertTrue(SaTokenConfigure.isWorkspaceStorageEndpoint("GET","/admin/storage/exist/key"));
        assertTrue(SaTokenConfigure.isWorkspaceStorageEndpoint("POST","/admin/storage/42/enable"));
        for (String path : new String[]{"/admin/users","/admin/tenants","/admin/storage-local-requests","/admin/storage/42/cors/apply","/admin/storage/../../users","/admin/storage/42/unknown"})
            for(String method : new String[]{"GET","POST","PUT","DELETE"}) assertFalse(SaTokenConfigure.isWorkspaceStorageEndpoint(method,path));
        assertFalse(SaTokenConfigure.isWorkspaceStorageEndpoint("PUT","/admin/storage/42"));
    }
    @Test void storageHelperAccessUsesFreshRoleInsteadOfOldToken() {
        var config = new SaTokenConfigure(); var users = mock(UserService.class); ReflectionTestUtils.setField(config,"userService",users);
        var actor = new User(); actor.setRole(UserRoleEnum.TENANT_ADMIN);
        when(users.requireLoginUser(10)).thenReturn(actor);
        try(var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(10);
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(config,"requireFreshStorageAdministrator"));
            actor.setRole(UserRoleEnum.USER);
            assertThrows(NotRoleException.class, () -> ReflectionTestUtils.invokeMethod(config,"requireFreshStorageAdministrator"));
            verify(users,times(2)).requireLoginUser(10);
        }
    }
}
