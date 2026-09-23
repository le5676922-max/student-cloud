package im.tlgxy.cloud.core.aspect;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.extra.servlet.JakartaServletUtil;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.RequestHolder;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接口限流切面，通过拦截带有 {@link ApiLimit} 注解的方法来实现接口级别的访问频率限制。
 * <p>
 * 核心原理：<br>
 * 1. 以 "IP + 方法名" 作为缓存 key，使用 Hutool 的 TimedCache 进行计数。<br>
 * 2. 当请求到达时，检查缓存中是否存在该 key：
 *    - 不存在则创建，并将计数初始化为 1，设置过期时间为注解指定的时间窗口；
 *    - 存在则原子性自增计数，若超过最大次数则抛出业务异常。
 * </p>
 * <p>
 * TimedCache 会自动在超时后清除缓存条目，实现时间窗口的自动重置。
 * </p>
 *
 * @author zhaojun
 */
// 声明此类为切面类
@Aspect
// 声明为 Spring 组件，交由 Spring 容器管理
@Component
public class ApiLimitAspect {

    /**
     * 基于时间的缓存，用于存储每个 IP 对应每个接口的访问次数。
     * key 格式为 "api_limit_" + IP + 方法名，value 为原子计数器。
     */
    private final TimedCache<String, AtomicLong> apiLimitTimedCache = CacheUtil.newTimedCache(1000);

    /**
     * 限流缓存 key 的前缀，用于区分不同的限流场景
     */
    public static final String API_LIMIT_KEY_PREFIX = "api_limit_";

    /**
     * 在标记了 {@link ApiLimit} 注解的方法执行前进行限流校验。
     * <p>
     * 当请求频率超过注解设定的阈值时，抛出 {@link BizException} 异常阻止方法执行。
     * </p>
     *
     * @param joinPoint 切点，包含了被拦截方法的相关信息
     * @param apiLimit 方法上的限流注解实例，包含时间窗口和最大次数等配置
     */
    @Before("@annotation(apiLimit)")
    public void before(JoinPoint joinPoint, ApiLimit apiLimit) {
        // 获取当前请求的方法上的注解中设置的值
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 通过反射获取当前被调用的方法对象
        Method method = signature.getMethod();
        int timeout = apiLimit.timeout();
        TimeUnit timeUnit = apiLimit.timeUnit();
        // 将时间窗口转换为毫秒，供 TimedCache 使用
        long millis = timeUnit.toMillis(timeout);
        long maxCount = apiLimit.maxCount();

        // 获取请求的客户端 IP 地址，用于按 IP 维度进行限流
        String ip = JakartaServletUtil.getClientIP(RequestHolder.getRequest());

        // 构建缓存 key：前缀 + IP + 方法名，确保不同 IP 和不同方法的限流互相独立
        String key = API_LIMIT_KEY_PREFIX.concat(ip).concat(method.getName());
        // 尝试从缓存中获取该 key 的计数器
        AtomicLong atomicLong = apiLimitTimedCache.get(key, false);
        if (atomicLong == null) {
            // 首次访问，创建计数器并初始化为 1，设置过期时间（时间窗口）
            apiLimitTimedCache.put(key, new AtomicLong(1), millis);
        } else {
            // 非首次访问，原子性自增并检查是否超过最大次数
            if (atomicLong.incrementAndGet() > maxCount) {
                // 超过最大次数，抛出访问过于频繁的业务异常
                throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
            }
        }
    }

}
