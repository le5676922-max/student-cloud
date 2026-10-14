package im.tlgxy.cloud.module.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.service.login.verify.impl.PasswordVerifyService;
import im.tlgxy.cloud.module.user.utils.PasswordCredentialFingerprint;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordRecoveryCredentialRaceTest {
    @Test void oldPasswordVerificationCannotAcquireNewFingerprintWhenPasswordChangesBeforeTokenCreation() {
        var encoded = PasswordVerifyUtils.encrypt("OldPassword123");
        User verified = new User(); verified.setId(9); verified.setPassword(encoded.getFirst()); verified.setSalt(encoded.getSecond());
        var newEncoded = PasswordVerifyUtils.encrypt("NewPassword123");
        User current = new User(); current.setId(9); current.setPassword(newEncoded.getFirst()); current.setSalt(newEncoded.getSecond());
        UserService users = mock(UserService.class);
        when(users.requireLoginUser(9)).thenReturn(verified, current);
        PasswordVerifyService verifier = new PasswordVerifyService();
        ReflectionTestUtils.setField(verifier, "userService", users);
        UserLoginRequest request = new UserLoginRequest(); request.setResolvedUserId(9); request.setPassword("OldPassword123");
        verifier.verify(request);
        assertTrue(PasswordCredentialFingerprint.matches(request.getVerifiedCredentialFingerprint(), verified));
        assertFalse(PasswordCredentialFingerprint.matches(request.getVerifiedCredentialFingerprint(), current),
                "login controller and fresh token interceptor must reject this marker after reset");
    }

    @Test void browserCannotInjectServerVerifiedFingerprintOrResolvedPrincipal() throws Exception {
        var request = new ObjectMapper().readValue("{\"username\":\"member\",\"password\":\"secret\",\"resolvedUserId\":1,\"verifiedCredentialFingerprint\":\"forged\"}", UserLoginRequest.class);
        assertNull(request.getResolvedUserId());
        assertNull(request.getVerifiedCredentialFingerprint());
    }
}
