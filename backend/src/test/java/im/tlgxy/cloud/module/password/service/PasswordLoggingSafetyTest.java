package im.tlgxy.cloud.module.password.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import im.tlgxy.cloud.module.password.mapper.PasswordConfigMapper;
import im.tlgxy.cloud.module.password.model.entity.PasswordConfig;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordLoggingSafetyTest {
    @Test
    void debugLogsNeverContainSubmittedOrConfiguredPasswords() {
        String configured = "synthetic-configured-secret-731";
        String submitted = "synthetic-submitted-secret-992";
        PasswordConfigMapper mapper = mock(PasswordConfigMapper.class);
        UserStorageSourceService permissions = mock(UserStorageSourceService.class);
        PasswordConfigService target = new PasswordConfigService();
        ReflectionTestUtils.setField(target, "passwordConfigMapper", mapper);
        ReflectionTestUtils.setField(target, "userStorageSourceService", permissions);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setExposeProxy(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
        PasswordConfigService service = (PasswordConfigService) factory.getProxy();
        Logger logger = (Logger) LoggerFactory.getLogger(PasswordConfigService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        try {
            when(permissions.hasCurrentUserStorageOperatorPermission(anyInt(), any())).thenReturn(true);
            service.verifyPassword(9, "/", submitted);
            when(permissions.hasCurrentUserStorageOperatorPermission(anyInt(), any())).thenReturn(false);
            when(mapper.findByStorageId(9)).thenReturn(List.of());
            service.verifyPassword(9, "/", submitted);
            PasswordConfig rule = new PasswordConfig();
            rule.setPassword(configured);
            rule.setExpression("/**");
            when(mapper.findByStorageId(9)).thenReturn(List.of(rule));
            service.verifyPassword(9, "/", "");
            service.verifyPassword(9, "/", submitted);
            service.verifyPassword(9, "/", configured);
            service.batchSave(9, List.of(rule));
            rule.setExpression("[");
            assertFalse(service.verifyPassword(9, "/", submitted).isPassed(),
                    "Invalid protection patterns must fail closed rather than expose the directory");
            assertFalse(events.list.isEmpty());
            String logs = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(logs.contains(configured));
            assertFalse(logs.contains(submitted));
            assertTrue(events.list.stream().noneMatch(event -> event.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(events);
            events.stop();
            logger.setLevel(previous);
        }
    }
}
