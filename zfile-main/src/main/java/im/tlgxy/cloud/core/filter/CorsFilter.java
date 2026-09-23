package im.tlgxy.cloud.core.filter;

import im.tlgxy.cloud.core.config.CorsProperties;
import im.tlgxy.cloud.core.constant.TlgxyCloudHttpHeaderConstant;
import im.tlgxy.cloud.core.util.StringUtils;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 跨域资源共享（CORS）过滤器
 * <p>
 * 为所有请求添加 CORS 响应头，允许前端从不同的域名或端口访问后端 API。
 * 一般用于以下场景：
 * <ul>
 *   <li>开发环境：前端（Vue）运行在 localhost:5173，后端运行在 localhost:8080</li>
 *   <li>生产环境：前后端分离部署在不同的域名下</li>
 * </ul>
 * </p>
 * <p>
 * 该过滤器的优先级设置为 {@link Integer#MIN_VALUE}，确保在其他过滤器之前执行。
 * 对 favicon.ico 请求会跳过处理（减少不必要的 CORS 头设置）。
 * 对预检请求（OPTIONS）不做链式传递，直接由过滤器响应。
 * </p>
 *
 * @author zhaojun
 */
@WebFilter(urlPatterns = "/*")
@Order(Integer.MIN_VALUE)
@Component
@RequiredArgsConstructor
public class CorsFilter implements Filter {

    private final CorsProperties corsProperties;

    /**
     * 执行跨域过滤逻辑
     * <p>
     * 为每个请求设置以下 CORS 响应头：
     * <ul>
     *   <li>Access-Control-Allow-Origin：允许的源，取请求头中的 Origin 值，如果为空则设为 *</li>
     *   <li>Access-Control-Allow-Headers：允许的请求头，包括自定义的 TlgxyCloud Token 和 Axios 相关头</li>
     *   <li>Access-Control-Allow-Methods：允许的 HTTP 方法</li>
     *   <li>Access-Control-Allow-Credentials：是否允许携带凭证（Cookie 等）</li>
     *   <li>Access-Control-Max-Age：预检请求的缓存时间（秒）</li>
     * </ul>
     * </p>
     *
     * @param request  客户端请求
     * @param response 服务端响应
     * @param chain    过滤器链
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;

        // 跳过 favicon.ico 请求，减少不必要的处理
        if (httpServletRequest.getRequestURI().equals("/favicon.ico")) {
            return;
        }

        // 获取请求来源（Origin 头）
        String header = httpServletRequest.getHeader(HttpHeaders.ORIGIN);

        // 构建允许的请求头列表，包含标准头和 TlgxyCloud 自定义头
        List<String> allowHeaders = Arrays.asList("Origin", "X-Requested-With", "Content-Type", "Accept", TlgxyCloudHttpHeaderConstant.TLGXY_CLOUD_TOKEN, TlgxyCloudHttpHeaderConstant.AXIOS_REQUEST, TlgxyCloudHttpHeaderConstant.AXIOS_FROM);
        boolean allowedOrigin = corsProperties.isAllowed(header);
        if (allowedOrigin) {
            httpServletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, header);
            httpServletResponse.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            httpServletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, StringUtils.join(",", allowHeaders));
            httpServletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            httpServletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "false");
            httpServletResponse.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600");
        }

        if (CorsUtils.isPreFlightRequest(httpServletRequest)) {
            if (!allowedOrigin) {
                httpServletResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed");
            }
            return;
        }
        chain.doFilter(httpServletRequest, httpServletResponse);
    }

}
