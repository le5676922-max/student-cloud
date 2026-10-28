package io.studentcloud.runtime.http;

import tools.jackson.databind.ObjectMapper;
import io.studentcloud.account.SessionAuthenticator;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(10)
public class AuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_GET = Set.of("/healthz", "/internal/ready", "/api/install/status",
            "/api/site/config/global", "/api/sso/list", "/user/login/check", "/user/login/verify-mode",
            "/user/login/entry/validate", "/user/registration/config", "/user/password-reset/config");
    private static final Set<String> PUBLIC_POST = Set.of("/api/install", "/user/login", "/user/register",
            "/user/registration/email-code", "/user/password-reset/email-code", "/user/password-reset");
    private final SessionAuthenticator accounts;
    private final ObjectMapper json;

    public AuthenticationFilter(SessionAuthenticator accounts, ObjectMapper json) { this.accounts = accounts; this.json = json; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod())
                || request.getRequestURI().startsWith("/internal/")
                || ("GET".equals(request.getMethod()) && "/readyz".equals(request.getRequestURI()))
                // The exact upgrade endpoint uses one-use tickets; HTTP APIs still require bearer credentials.
                || ("GET".equals(request.getMethod()) && "/ws/support".equals(request.getRequestURI()))
                || ("GET".equals(request.getMethod()) && PUBLIC_GET.contains(request.getRequestURI()))
                || ("POST".equals(request.getMethod()) && PUBLIC_POST.contains(request.getRequestURI()));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Actor actor = accounts.authenticate(RequestTokens.extract(request));
            request.setAttribute(Actor.class.getName(), actor);
        } catch (ApplicationFault rejected) {
            response.setStatus(rejected.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), ApiResponse.failure(rejected.code(), rejected.getMessage(), UUID.randomUUID().toString()));
            return;
        }
        chain.doFilter(request, response);
    }

    public static Actor actor(HttpServletRequest request) {
        Object value = request.getAttribute(Actor.class.getName());
        if (value instanceof Actor actor) return actor;
        throw new ApplicationFault("42000", 401, "请先登录");
    }
}
