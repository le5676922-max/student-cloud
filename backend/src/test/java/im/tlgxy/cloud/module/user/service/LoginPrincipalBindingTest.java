package im.tlgxy.cloud.module.user.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.login.verify.impl.PasswordVerifyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginPrincipalBindingTest {
    @Test
    void clientCannotChooseVerifiedPrincipal() throws Exception {
        var request = new ObjectMapper().readValue("{\"username\":\"member@example.test\",\"password\":\"fixture-password\",\"resolvedUserId\":1}", UserLoginRequest.class);
        assertNull(request.getResolvedUserId());
    }

    @Test
    void passwordAlwaysBelongsToResolvedIdNotReassignedUsername() {
        UserService users = mock(UserService.class);
        User bound = user(5, "own-password");
        User renamed = user(1, "another-password");
        when(users.requireLoginUser(5)).thenReturn(bound);
        when(users.getByUsername("former-username")).thenReturn(renamed);
        var verifier = new PasswordVerifyService();
        ReflectionTestUtils.setField(verifier, "userService", users);
        var request = new UserLoginRequest();
        request.setUsername("former-username");
        request.setResolvedUserId(5);
        request.setPassword("another-password");
        assertThrows(BizException.class, () -> verifier.verify(request));
        request.setPassword("own-password");
        assertDoesNotThrow(() -> verifier.verify(request));
        verify(users, never()).getByUsername(anyString());
    }

    @Test
    void manualUsernamesLeaveEmailAndAccountIdNamespacesReserved() {
        assertTrue(UserService.isReservedManualUsername("SC000000000001"));
        assertTrue(UserService.isReservedManualUsername("sc0123456789abcdef"));
        assertTrue(UserService.isReservedManualUsername("student@example.test"));
        assertFalse(UserService.isReservedManualUsername("student_123"));
    }

    private static User user(int id, String password) {
        User user = new User();
        user.setId(id);
        user.setPassword(SecureUtil.md5(password));
        return user;
    }
}
