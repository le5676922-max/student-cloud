package im.tlgxy.cloud.core.aspect;

import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.cache.ratelimit.TrustedClientIpResolver;
import im.tlgxy.cloud.core.exception.core.BizException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiLimitAspectTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void forwardsFullMethodIdentityAndUsesSocketIpWhenHeadersAreUntrusted() throws Exception {
        var limiter = mock(ApiRateLimiter.class);
        when(limiter.allow(anyString(), anyString(), anyLong(), anyLong())).thenReturn(true);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.4");
        request.addHeader("X-Forwarded-For", "192.0.2.99");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var method = Endpoints.class.getDeclaredMethod("operation", String.class);
        var point = point(method);
        new ApiLimitAspect(limiter, new TrustedClientIpResolver(List.of())).before(point, method.getAnnotation(ApiLimit.class));
        verify(limiter).allow(method.toGenericString(), "203.0.113.4", 2, 60_000);
    }

    @Test
    void refusalKeepsExistingBusinessErrorAndDoesNotExecuteController() throws Exception {
        var limiter = mock(ApiRateLimiter.class);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var method = Endpoints.class.getDeclaredMethod("operation", String.class);
        var aspect = new ApiLimitAspect(limiter, new TrustedClientIpResolver(List.of("172.30.0.0/24")));
        assertEquals("41005", assertThrows(BizException.class,
                () -> aspect.before(point(method), method.getAnnotation(ApiLimit.class))).getCode());
        verify(limiter).allow(method.toGenericString(), "203.0.113.4", 2, 60_000);
    }

    private JoinPoint point(java.lang.reflect.Method method) {
        var point = mock(JoinPoint.class);
        var signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(point.getSignature()).thenReturn(signature);
        return point;
    }

    private static class Endpoints {
        @ApiLimit(timeout = 60, maxCount = 2) public void operation(String input) { }
    }
}
