package im.tlgxy.cloud.module.link.aspect;

import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.model.enums.RefererTypeEnum;
import im.tlgxy.cloud.module.storage.annotation.RefererCheck;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.List;

/**
 * Referer 防盗链校验切面
 * <p>
 * 通过 AOP 环绕通知对所有标注了 {@link RefererCheck} 注解的方法进行 Referer 防盗链校验。
 * 支持三种防盗链模式：
 * <ul>
 *   <li>关闭模式（OFF）：不进行任何 Referer 校验</li>
 *   <li>白名单模式（WHITE_LIST）：只允许 Referer 匹配白名单规则的请求通过</li>
 *   <li>黑名单模式（BLACK_LIST）：阻止 Referer 匹配黑名单规则的请求</li>
 * </ul>
 * <p>
 * 支持 Ant 风格的路径匹配规则，可以灵活配置允许或禁止的 Referer 来源。
 * 当请求被拦截时，会将用户重定向到预设的禁止访问页面。
 *
 * @author zhaojun
 */
@Aspect
@Component
@Slf4j
public class RefererCheckAspect {

    /**
     * HTTP 请求对象，用于获取 Referer 头和请求 URI
     */
    @Resource
    private HttpServletRequest httpServletRequest;

    /**
     * HTTP 响应对象，用于重定向被拦截的请求
     */
    @Resource
    private HttpServletResponse httpServletResponse;

    /**
     * 系统配置服务，用于获取 Referer 防盗链相关配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * Ant 风格路径匹配器，用于匹配 Referer 规则
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 校验 Referer 防盗链的环绕通知
     * <p>
     * 拦截所有标注了 {@link RefererCheck} 注解的方法调用，在方法执行前校验请求的 Referer 头。
     * 校验流程：
     * <ol>
     *   <li>获取系统配置的 Referer 类型</li>
     *   <li>如果关闭了 Referer 校验，直接放行</li>
     *   <li>检查 Referer 是否允许为空</li>
     *   <li>根据白名单/黑名单模式匹配 Referer 值</li>
     *   <li>被拦截的请求重定向到禁止访问页面</li>
     * </ol>
     *
     * @param   point
     *          切点连接点，包含被拦截方法的信息
     *
     * @return  被拦截方法的原始返回值，被拦截时返回 null
     *
     * @throws Throwable 方法执行过程中可能抛出的异常
     */
    @Around(value = "@annotation(im.tlgxy.cloud.module.storage.annotation.RefererCheck)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        // 获取配置的 referer 类型
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        RefererTypeEnum refererType = systemConfig.getRefererType();

        // 如果未开启 referer 防盗链则跳过.
        if (refererType == RefererTypeEnum.OFF) {
            return point.proceed();
        }

        // 获取当前请求 referer
        String referer = httpServletRequest.getHeader(HttpHeaders.REFERER);
        String requestUrl = httpServletRequest.getRequestURI();

        // 获取 Forbidden 页面地址
        String forbiddenUrl = systemConfigService.getForbiddenUrl();

        // 如果 referer 不允许为空，且当前 referer 为空，则校验
        Boolean refererAllowEmpty = systemConfig.getRefererAllowEmpty();
        if (!refererAllowEmpty && StringUtils.isEmpty(referer)) {
            log.warn("请求路径 {}, referer 不允许为空，当前请求 referer 为空，禁止访问.", requestUrl);
            httpServletResponse.sendRedirect(forbiddenUrl);
            return null;
        } else if (refererAllowEmpty && StringUtils.isEmpty(referer)) { // 如果 referer 允许为空，且当前 referer 为空，则跳过校验
            return point.proceed();
        }

        // 获取允许的 referer 地址
        String refererValue = systemConfig.getRefererValue();
        List<String> refererValueList = StringUtils.split(refererValue, StringUtils.LF);

        // 如果是白名单模式，则校验当前 referer, 如果未在允许的列表中，则禁止访问.
        if (refererType == RefererTypeEnum.WHITE_LIST && containsPathMatcher(refererValueList, referer) == null) {
            log.warn("请求路径 {}, referer 为白名单模式，当前请求 referer {} 未在白名单中，禁止访问.", requestUrl, referer);
            httpServletResponse.sendRedirect(forbiddenUrl);
            return null;
        }

        // 如果是黑名单模式，则校验当前 referer 是否在列表中，则禁止访问.
        if (refererType == RefererTypeEnum.BLACK_LIST && containsPathMatcher(refererValueList, referer) != null) {
            log.warn("请求路径 {}, referer 为黑名单模式，当前请求 referer {} 在黑名单中，禁止访问.", requestUrl, referer);

            httpServletResponse.sendRedirect(forbiddenUrl);
            return null;
        }

        return point.proceed();
    }

    /**
     * 校验 value 是否匹配 Ant 表达式列表中的任意规则
     * <p>
     * 使用 {@link AntPathMatcher} 进行 Ant 风格的路径匹配，
     * 支持通配符如 ?（匹配单个字符）、*（匹配零个或多个字符）、**（匹配多级路径）。
     *
     * @param   patternList
     *          Ant 表达式模式列表，用于匹配的规则集合
     *
     * @param   value
     *          要校验的 Referer 值
     *
     * @return  返回匹配到的第一个规则项，如果没有匹配到则返回 null
     */
    public String containsPathMatcher(Collection<String> patternList, String value) {
        if (CollectionUtils.isEmpty(patternList)) {
            return null;
        }

        for (String pattern : patternList) {
            if (pathMatcher.match(pattern, value)) {
                return pattern;
            }
        }

        return null;
    }

}