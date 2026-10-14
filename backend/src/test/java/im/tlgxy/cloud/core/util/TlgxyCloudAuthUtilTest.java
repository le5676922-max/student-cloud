package im.tlgxy.cloud.core.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TlgxyCloudAuthUtilTest {
    @Test
    void currentUserDoesNotKeepAServiceFromAnOldApplicationContext() {
        UserService first = mock(UserService.class);
        UserService second = mock(UserService.class);
        User oldUser = new User(); oldUser.setId(6); oldUser.setEnable(true);
        User newUser = new User(); newUser.setId(6); newUser.setEnable(true);
        when(first.requireLoginUser(6)).thenReturn(oldUser);
        when(second.requireLoginUser(6)).thenReturn(newUser);
        try (var spring = mockStatic(SpringUtil.class); var tokens = mockStatic(StpUtil.class)) {
            spring.when(() -> SpringUtil.getBean(UserService.class)).thenReturn(first, second);
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(6);
            assertSame(oldUser, TlgxyCloudAuthUtil.getCurrentUser());
            assertSame(newUser, TlgxyCloudAuthUtil.getCurrentUser());
        }
    }

    @Test
    void currentUserIdDoesNotLoadUnneededServiceBeans() {
        try (var spring = mockStatic(SpringUtil.class); var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(6);
            assertEquals(6, TlgxyCloudAuthUtil.getCurrentUserId());
            spring.verifyNoInteractions();
        }
    }

    @Test
    void missingOrExpiredSessionNeverFallsBackToGuestOrShareOwner() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenThrow(new IllegalStateException("expired session"));
            assertThrows(IllegalStateException.class, TlgxyCloudAuthUtil::getCurrentUserId);
            ShareAccessContext.setShareAccess("share", "/", 6);
            assertThrows(IllegalStateException.class, TlgxyCloudAuthUtil::getCurrentUserId);
        } finally {
            ShareAccessContext.clear();
        }
    }

    @Test
    void historicalGuestSessionIsRejected() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(UserConstant.ANONYMOUS_ID);
            assertThrows(BizException.class, TlgxyCloudAuthUtil::getCurrentUserId);
        }
    }

    @Test
    void disabledUserDoesNotBecomeAnAnonymousUser() {
        UserService users = mock(UserService.class);
        when(users.requireLoginUser(6)).thenThrow(new BizException("disabled"));
        try (var spring = mockStatic(SpringUtil.class); var tokens = mockStatic(StpUtil.class)) {
            spring.when(() -> SpringUtil.getBean(UserService.class)).thenReturn(users);
            tokens.when(StpUtil::getLoginIdAsInt).thenReturn(6);
            assertThrows(BizException.class, TlgxyCloudAuthUtil::getCurrentUser);
        }
    }
}
