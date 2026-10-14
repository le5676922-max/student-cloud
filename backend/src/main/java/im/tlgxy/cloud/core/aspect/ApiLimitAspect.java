package im.tlgxy.cloud.core.aspect;

import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.cache.ratelimit.TrustedClientIpResolver;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.RequestHolder;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/** Per-client, fully-qualified-method limiting; never trust arbitrary proxy headers. */
@Aspect
@Component
@RequiredArgsConstructor
public class ApiLimitAspect {
    public static final String API_LIMIT_KEY_PREFIX = "api_limit_";
    private final ApiRateLimiter limiter;
    private final TrustedClientIpResolver clientIp;

    @Before("@annotation(apiLimit)")
    public void before(JoinPoint joinPoint, ApiLimit apiLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String scope = signature.getMethod().toGenericString();
        String subject = clientIp.resolve(RequestHolder.getRequest());
        if (!limiter.allow(scope, subject, apiLimit.maxCount(), apiLimit.timeUnit().toMillis(apiLimit.timeout()))) {
            throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
        }
    }
}
