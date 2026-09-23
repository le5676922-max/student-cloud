package im.tlgxy.cloud.core.util;

import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring MVC 工具类
 * <p>
 * 提供与 Spring MVC 框架相关的便捷工具方法，如提取请求路径中匹配通配符模式的实际路径部分。
 * 主要封装了 Spring MVC 的 {@link HandlerMapping} 属性和 {@link AntPathMatcher} 的使用。
 * </p>
 *
 * @author zhaojun
 */
public class SpringMvcUtils {

    /**
     * 从当前请求中提取路径变量对应的实际路径部分
     * <p>
     * 当 Spring MVC 的 URL 映射使用了 Ant 风格的通配符模式（如 <code>/api/**</code>）时，
     * 该方法可以从请求的完整路径中提取出匹配通配符的那部分路径。
     * 例如：
     * <ul>
     *   <li>URL 映射模式：<code>/api/**</code></li>
     *   <li>实际请求路径：<code>/api/user/list</code></li>
     *   <li>提取结果：<code>user/list</code></li>
     * </ul>
     * 该方法依赖于 Spring MVC 在请求属性中设置的
     * {@link HandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} 和
     * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 两个属性，
     * 因此只能在 Spring MVC 处理请求的线程中调用。
     * </p>
     *
     * @return  通配符模式匹配的路径部分字符串，即实际请求路径中对应通配符的那段路径
     */
    public static String getExtractPathWithinPattern() {
        // 从 RequestHolder 获取当前线程绑定的 HttpServletRequest
        HttpServletRequest httpServletRequest = RequestHolder.getRequest();
        // 获取请求的完整路径（去掉上下文路径后的部分）
        String path = (String) httpServletRequest.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        // 获取 Spring MVC 匹配到的最佳 URL 映射模式（可能包含通配符，如 /api/**）
        String bestMatchPattern = (String) httpServletRequest.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // 使用 AntPathMatcher 从完整路径中提取匹配通配符的部分
        AntPathMatcher apm = new AntPathMatcher();
        return apm.extractPathWithinPattern(bestMatchPattern, path);
    }

}