package im.tlgxy.cloud.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.LoginVerifyModeEnum;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import im.tlgxy.cloud.module.user.model.result.LoginResult;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.login.ImgVerifyCodeService;
import im.tlgxy.cloud.module.user.service.login.LoginService;
import im.tlgxy.cloud.module.user.service.login.TwoFactorAuthenticatorVerifyService;
import im.tlgxy.cloud.module.user.service.login.verify.impl.ImgCodeLoginVerifyService;
import im.tlgxy.cloud.module.user.service.login.verify.impl.TwoFactorAuthLoginVerifyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLoginAliasTest {
    @ParameterizedTest
    @ValueSource(strings = {" Admin@Example.COM ", "SC0123456789ABCDEF", "legacy-admin"})
    void everyAdministratorAliasReachesTheExistingTwoFactorVerifier(String alias) {
        UserService users = mock(UserService.class);
        when(users.getByLoginIdentifier(alias)).thenReturn(admin());
        when(users.isAdmin(1)).thenReturn(true);
        SystemConfigService configs = mock(SystemConfigService.class);
        when(configs.getSystemConfig()).thenReturn(twoFactorConfig());
        ImgVerifyCodeService captcha = mock(ImgVerifyCodeService.class);
        ImgCodeLoginVerifyService imageVerifier = new ImgCodeLoginVerifyService();
        ReflectionTestUtils.setField(imageVerifier, "userService", users);
        ReflectionTestUtils.setField(imageVerifier, "systemConfigService", configs);
        ReflectionTestUtils.setField(imageVerifier, "imgVerifyCodeService", captcha);
        TwoFactorAuthenticatorVerifyService twoFactor = mock(TwoFactorAuthenticatorVerifyService.class);
        BizException rejected = new BizException(ErrorCode.BIZ_2FA_CODE_ERROR);
        doThrow(rejected).when(twoFactor).checkCode("unit-test-2fa-secret", "000000");
        TwoFactorAuthLoginVerifyService twoFactorVerifier = new TwoFactorAuthLoginVerifyService();
        ReflectionTestUtils.setField(twoFactorVerifier, "userService", users);
        ReflectionTestUtils.setField(twoFactorVerifier, "systemConfigService", configs);
        ReflectionTestUtils.setField(twoFactorVerifier, "twoFactorAuthVerifyService", twoFactor);
        LoginService login = new LoginService();
        ReflectionTestUtils.setField(login, "loginVerifyServiceList", List.of(imageVerifier, twoFactorVerifier));
        UserController controller = controller(users, configs, login);
        UserLoginRequest request = new UserLoginRequest();
        request.setUsername(alias);
        request.setPassword("unit-test-password");
        request.setVerifyCode("000000");

        assertSame(rejected, assertThrows(BizException.class, () -> controller.doLogin(request)));
        assertEquals("legacy-admin", request.getUsername());
        assertEquals(1, request.getResolvedUserId());
        verify(users, atLeastOnce()).isAdmin(1);
        verify(users, never()).isAdmin(anyString());
        verify(twoFactor).checkCode("unit-test-2fa-secret", "000000");
        verifyNoInteractions(captcha);
        verify(users, never()).requireLoginUser(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {" Admin@Example.COM ", "SC0123456789ABCDEF", "legacy-admin"})
    void verificationModeResolvesAliasesToTheRealAdministrator(String alias) {
        UserService users = mock(UserService.class);
        when(users.getByLoginIdentifier(alias)).thenReturn(admin());
        when(users.isAdmin(1)).thenReturn(true);
        SystemConfigService configs = mock(SystemConfigService.class);
        when(configs.getSystemConfig()).thenReturn(twoFactorConfig());
        var result = controller(users, configs, mock(LoginService.class)).loginVerifyMode(alias);
        assertEquals(LoginVerifyModeEnum.TWO_FACTOR_AUTHENTICATION_MODE, result.getData());
        verify(users).isAdmin(1);
        verify(users, never()).isAdmin(anyString());
    }

    @Test
    void unknownAccountGetsGenericCredentialsErrorBeforeSessionCreation() {
        LoginService login = mock(LoginService.class);
        UserLoginRequest request = new UserLoginRequest();
        request.setUsername("missing@example.com");
        request.setPassword("unit-test-password");
        BizException error = assertThrows(BizException.class,
                () -> controller(mock(UserService.class), mock(SystemConfigService.class), login).doLogin(request));
        assertEquals(ErrorCode.BIZ_LOGIN_ERROR.getCode(), error.getCode());
        verifyNoInteractions(login);
    }

    @Test
    void loginResultExposesIdentifiersWithoutCredentials() throws Exception {
        User user = admin();
        user.setAccountId("SC0123456789ABCDEF");
        user.setEmail("admin@example.com");
        user.setPassword("credential-value");
        user.setSalt("salt-value");
        var json = new ObjectMapper().valueToTree(new LoginResult("test-token", user));
        assertEquals("SC0123456789ABCDEF", json.get("accountId").asText());
        assertEquals("admin@example.com", json.get("email").asText());
        assertFalse(json.has("password"));
        assertFalse(json.has("salt"));
    }

    private UserController controller(UserService users, SystemConfigService configs, LoginService login) {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "systemConfigService", configs);
        ReflectionTestUtils.setField(controller, "loginService", login);
        return controller;
    }

    private User admin() {
        User user = new User();
        user.setId(1);
        user.setUsername("legacy-admin");
        user.setRole(UserRoleEnum.ADMIN);
        user.setEnable(true);
        return user;
    }

    private SystemConfigDTO twoFactorConfig() {
        SystemConfigDTO config = new SystemConfigDTO();
        config.setLoginImgVerify(true);
        config.setAdminTwoFactorVerify(true);
        config.setLoginVerifySecret("unit-test-2fa-secret");
        return config;
    }
}
