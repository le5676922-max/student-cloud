package im.tlgxy.cloud.module.link.aspect;

import cn.hutool.extra.servlet.JakartaServletUtil;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.cache.LinkRateLimiterCache;
import im.tlgxy.cloud.module.storage.annotation.LinkRateLimiter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直链/短链访问频率限制切面
 * <p>
 * 通过 AOP 环绕通知对所有标注了 {@link LinkRateLimiter} 注解的方法进行访问频率校验。
 * 基于客户端 IP 地址进行限流计数，防止单个 IP 在指定时间窗口内频繁访问下载链接。
 * <p>
 * 工作原理：
 * <ol>
 *   <li>获取系统配置中的限流参数（限制时间窗口和最大访问次数）</li>
 *   <li>如果未配置限流参数或参数为 0，则跳过校验</li>
 *   <li>获取客户端 IP，在缓存中查找该 IP 的访问计数</li>
 *   <li>如果超过限制次数则抛出 {@link BizException} 异常</li>
 *   <li>否则增加计数或新建计数记录</li>
 * </ol>
 *
 * @author zhaojun
 */
@Aspect
@Component
@Slf4j
public class LinkRateLimiterAspect {

    /**
     * HTTP 请求对象，用于获取客户端 IP 地址
     */
    @Resource
    private HttpServletRequest httpServletRequest;

    /**
     * 系统配置服务，用于获取限流相关配置参数
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 链接频率限制缓存，用于存储各 IP 的访问计数
     */
    @Resource
    private LinkRateLimiterCache linkRateLimiterCache;

    /**
     * 校验直链/短链访问频率的环绕通知
     * <p>
     * 拦截所有标注了 {@link LinkRateLimiter} 注解的方法调用，
     * 在方法执行前检查客户端 IP 的访问频率是否超过配置的限制。
     *
     * @param   point
     *          切点连接点，包含被拦截方法的信息
     *
     * @return  被拦截方法的原始返回值
     *
     * @throws Throwable 当访问频率超限时抛出 {@link BizException}，或方法执行过程中抛出其他异常
     */
    @Around(value = "@annotation(im.tlgxy.cloud.module.storage.annotation.LinkRateLimiter)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        // 获取系统配置中的限流参数
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        Integer linkLimitSecond = systemConfig.getLinkLimitSecond();
        Integer linkDownloadLimit = systemConfig.getLinkDownloadLimit();
        // 如果未设置直链限制, 则不进行校验
        if (linkLimitSecond == null || linkDownloadLimit == null || linkLimitSecond == 0 || linkDownloadLimit == 0) {
            return point.proceed();
        }

        // 获取客户端 IP 地址
        String clientIP = JakartaServletUtil.getClientIP(httpServletRequest);
        if (linkRateLimiterCache.containsKey(clientIP)) {
            // IP 已在缓存中，增加计数并检查是否超限
            AtomicInteger atomicInteger = linkRateLimiterCache.get(clientIP, false);
            if (atomicInteger.incrementAndGet() > linkDownloadLimit) {
                // 访问频率超限，抛出业务异常
                throw new BizException(ErrorCode.BIZ_ACCESS_TOO_FREQUENT);
            }
        } else {
            // IP 首次访问，创建计数记录，设置过期时间为配置的限制秒数
            linkRateLimiterCache.put(clientIP, new AtomicInteger(1), linkLimitSecond * 1000);
        }

        // 校验通过，继续执行原方法
        return point.proceed();
    }

}