package im.tlgxy.cloud.module.user.aspect;

import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import im.tlgxy.cloud.module.log.service.LoginLogService;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginLogAspectTest {
    @ParameterizedTest
    @EnumSource(LoginLogModeEnum.class)
    void passwordsAreNeverRecordedIncludingLegacyModes(LoginLogModeEnum mode) throws Throwable {
        for (boolean failure : new boolean[]{false, true}) {
            var config = new SystemConfigDTO();
            config.setLoginLogMode(mode);
            var configs = mock(SystemConfigService.class);
            when(configs.getSystemConfig()).thenReturn(config);
            var logs = mock(LoginLogService.class);
            var aspect = new LoginLogAspect();
            ReflectionTestUtils.setField(aspect, "systemConfigService", configs);
            ReflectionTestUtils.setField(aspect, "loginLogService", logs);
            ReflectionTestUtils.setField(aspect, "httpServletRequest", new MockHttpServletRequest());
            var request = spy(new UserLoginRequest());
            request.setUsername("regression-user");
            request.setPassword("never-log-this-password");
            var joinPoint = mock(ProceedingJoinPoint.class);
            when(joinPoint.getArgs()).thenReturn(new Object[]{request});
            if (failure) {
                var error = new IllegalArgumentException("登录失败");
                when(joinPoint.proceed()).thenThrow(error);
                assertSame(error, assertThrows(IllegalArgumentException.class, () -> aspect.around(joinPoint)));
            } else {
                when(joinPoint.proceed()).thenReturn("success");
                assertEquals("success", aspect.around(joinPoint));
            }
            verify(request, never()).getPassword();
            if (mode == LoginLogModeEnum.OFF) {
                verifyNoInteractions(logs);
            } else {
                var captured = ArgumentCaptor.forClass(LoginLog.class);
                verify(logs).save(captured.capture());
                assertEquals("******", captured.getValue().getPassword());
                assertEquals("regression-user", captured.getValue().getUsername());
                assertNotNull(captured.getValue().getCreateTime());
                assertEquals(0, captured.getValue().getCreateTime().getTime() % 1000);
                assertEquals(failure ? "登录失败" : "登录成功", captured.getValue().getResult());
            }
        }
    }
}
