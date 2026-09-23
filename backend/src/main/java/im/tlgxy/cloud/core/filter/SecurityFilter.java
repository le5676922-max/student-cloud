package im.tlgxy.cloud.core.filter;

import cn.hutool.extra.servlet.JakartaServletUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.core.constant.RuleTypeConstant;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.matcher.IRuleMatcher;
import im.tlgxy.cloud.core.util.matcher.RuleMatcherFactory;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;

/**
 * 安全访问过滤器
 * <p>
 * 在请求进入业务逻辑之前，检查当前访问的客户端 IP 地址和 User-Agent 是否在系统配置的黑名单中。
 * 如果匹配黑名单规则，直接返回 HTTP 403 Forbidden 状态码并终止请求处理。
 * </p>
 * <p>
 * 安全策略：
 * <ul>
 *   <li><b>IP 黑名单</b>：基于 IP 地址的访问控制，支持单个 IP、IP 段、通配符等规则</li>
 *   <li><b>UA 黑名单</b>：基于 User-Agent 的访问控制，支持模糊匹配</li>
 * </ul>
 * 这两种黑名单可以单独或同时使用，规则通过系统管理后台配置。
 * </p>
 * <p>
 * {@link SystemConfigService} 采用懒加载 + 双重检测锁的方式初始化，避免在过滤器构造时
 * Spring 容器尚未完成初始化的问题。
 * </p>
 *
 * @author zhaojun
 */
@WebFilter(urlPatterns = "/*")
public class SecurityFilter implements Filter {

    /**
     * 系统配置服务，使用 volatile + 双重检测锁实现线程安全的懒加载
     */
    private static volatile SystemConfigService systemConfigService;

    /**
     * 执行安全访问检查
     * <p>
     * 依次检查 IP 黑名单和 UA 黑名单，任一命中则返回 403 并终止请求。
     * </p>
     *
     * @param request     客户端请求
     * @param response    服务端响应
     * @param filterChain 过滤器链
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;

        // 双重检测锁（DCL）实现 SystemConfigService 的懒加载，避免 Spring 容器未初始化时获取失败
        if (systemConfigService == null) {
            synchronized (this) {
                if (systemConfigService == null) {
                    systemConfigService = SpringUtil.getBean(SystemConfigService.class);
                }
            }
        }

        // 获取系统配置中的安全规则
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String accessIpBlocklist = systemConfig.getAccessIpBlocklist();
        String accessUaBlocklist = systemConfig.getAccessUaBlocklist();

        // 判断当前访问 IP 是否在黑名单中
        String currentAccessIp = JakartaServletUtil.getClientIP(httpServletRequest);
        if (StringUtils.isNotBlank(accessIpBlocklist) && checkIsDisableIP(accessIpBlocklist, currentAccessIp)) {
            // IP 命中黑名单，返回 403 并输出被拒绝的 IP 信息
            httpServletResponse.setStatus(HttpStatus.FORBIDDEN.value());
            httpServletResponse.getWriter().write("disable access.[" + currentAccessIp + "]");
            return;
        }

        // 判断当前访问 User-Agent 是否在黑名单中
        String userAgent = httpServletRequest.getHeader(HttpHeaders.USER_AGENT);
        if (StringUtils.isNotBlank(accessUaBlocklist) && checkIsDisableUA(accessUaBlocklist, userAgent)) {
            // UA 命中黑名单，返回 403 并输出被拒绝的 UA 信息
            httpServletResponse.setStatus(HttpStatus.FORBIDDEN.value());
            httpServletResponse.getWriter().write("disable access.[" + userAgent + "]");
            return;
        }

        // 通过安全检查，继续执行过滤器链
        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }

    /**
     * 检查当前 IP 是否匹配 IP 黑名单规则
     *
     * @param accessIpBlocklist 系统配置的 IP 黑名单规则（换行分隔的多条规则）
     * @param currentAccessIp   当前请求的客户端 IP 地址
     * @return 如果匹配任一黑名单规则返回 true，否则返回 false
     */
    private boolean checkIsDisableIP(String accessIpBlocklist, String currentAccessIp) {
        // 获取 IP 类型的规则匹配器
        IRuleMatcher ruleMatcher = RuleMatcherFactory.getRuleMatcher(RuleTypeConstant.IP);
        // 将黑名单字符串按换行符拆分为规则列表
        List<String> ruleList = StringUtils.split(accessIpBlocklist, StringUtils.LF);
        // 检查当前 IP 是否匹配任一规则
        return ruleMatcher.matchAny(ruleList, currentAccessIp);
    }

    /**
     * 检查当前 User-Agent 是否匹配 UA 黑名单规则
     *
     * @param accessUaBlocklist 系统配置的 UA 黑名单规则（换行分隔的多条规则）
     * @param currentAccessUA   当前请求的 User-Agent 字符串
     * @return 如果匹配任一黑名单规则返回 true，否则返回 false
     */
    private boolean checkIsDisableUA(String accessUaBlocklist, String currentAccessUA) {
        // 获取 Spring Simple 类型的规则匹配器（支持简单通配符）
        IRuleMatcher ruleMatcher = RuleMatcherFactory.getRuleMatcher(RuleTypeConstant.SPRING_SIMPLE);
        // 将黑名单字符串按换行符拆分为规则列表
        List<String> ruleList = StringUtils.split(accessUaBlocklist, StringUtils.LF);
        // 检查当前 UA 是否匹配任一规则
        return ruleMatcher.matchAny(ruleList, currentAccessUA);
    }

}
