package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaTokenConfigureFreshAdminTest {

    private final UserService users = mock(UserService.class);
    private final SaTokenConfigure configuration = new SaTokenConfigure();

    SaTokenConfigureFreshAdminTest() {
        ReflectionTestUtils.setField(configuration, "userService", users);
    }

    @Test
    void deniesAnonymousBeforeReadingDatabase() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::checkLogin).thenThrow(new BizException("not logged in"));
            assertThrows(BizException.class, this::checkFreshAdmin);
            verifyNoInteractions(users);
        }
    }

    @Test
    void deniesMemberOrWorkspaceAdministrator() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(10);
            when(users.isAdmin(10)).thenReturn(false);
            assertThrows(NotRoleException.class, this::checkFreshAdmin);
            verify(users).isAdmin(10);
            tokens.verify(() -> StpUtil.checkRole(anyString()), never());
        }
    }

    @Test
    void reevaluatesAccountAfterAdminTokenWasIssued() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(10);
            when(users.isAdmin(10)).thenReturn(true, false);
            assertDoesNotThrow(this::checkFreshAdmin);
            assertThrows(NotRoleException.class, this::checkFreshAdmin);
            verify(users, times(2)).isAdmin(10);
        }
    }

    private void checkFreshAdmin() {
        ReflectionTestUtils.invokeMethod(configuration, "requireFreshSystemAdmin");
    }
}
