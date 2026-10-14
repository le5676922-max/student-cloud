package im.tlgxy.cloud.core.filter;

import cn.hutool.core.util.IdUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.constant.MdcConstant;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * MDC（Mapped Diagnostic Context）日志追踪过滤器
 * <p>
 * 在每个 HTTP 请求处理开始前，向 SLF4J 的 MDC 中写入上下文信息（如 TraceId、客户端 IP、当前用户 ID），
 * 使得日志输出时能够自动携带这些信息，便于问题追踪和日志关联。
 * </p>
 * <p>
 * 写入的 MDC 信息包括：
 * <ul>
 *   <li><b>TraceId</b>：每次请求的唯一追踪标识（UUID），用于串联同一次请求的所有日志</li>
 *   <li><b>IP</b>：发起请求的客户端 IP 地址</li>
 *   <li><b>User</b>：当前登录用户的 ID</li>
 * </ul>
 * 请求处理完成后，在 finally 块中清除 MDC，防止信息泄漏到下一次请求。
 * </p>
 *
 * @author zhaojun
 */
@WebFilter(urlPatterns = "/*")
public class MDCFilter implements Filter {

	/**
	 * 在请求处理前写入 MDC 上下文信息，请求处理后清除
	 * <p>
	 * 使用 try-finally 确保无论请求处理成功还是抛出异常，MDC 都会被正确清理，
	 * 避免内存泄漏和请求间信息串扰。
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

		try {
            // Diagnostic identity is optional: public login/install requests have no session.
            // Business authorization is enforced later by the MVC interceptor, not the logger.
            MDC.put(MdcConstant.TRACE_ID, IdUtil.fastUUID());
            MDC.put(MdcConstant.IP, JakartaServletUtil.getClientIP(httpServletRequest));
            Object sessionUser = StpUtil.getLoginIdDefaultNull();
            if (sessionUser == null) MDC.remove(MdcConstant.USER);
            else MDC.put(MdcConstant.USER, sessionUser.toString());
			// 继续执行后续过滤器和业务逻辑
			filterChain.doFilter(httpServletRequest, httpServletResponse);
		} finally {
			// 请求处理完成后清除 MDC，防止内存泄漏和请求间数据串扰
			MDC.clear();
		}
	}

}
