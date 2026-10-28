package io.studentcloud.runtime.http;

import io.studentcloud.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Internal operations require a separate server credential even if a public bearer is supplied. */
@Order(5)
public final class InternalServiceKeyFilter extends OncePerRequestFilter {
    private final byte[] key;
    private final ObjectMapper json;
    public InternalServiceKeyFilter(@Value("${student-cloud.services.internal-key:}") String key,ObjectMapper json) {
        if (key.length()<32 || key.length()>200 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Set a random internal service key of at least 32 characters");
        this.key=key.getBytes(StandardCharsets.UTF_8); this.json=json;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String supplied=request.getHeader("X-StudentCloud-Service-Key");
        if (supplied==null || supplied.length()>200 || !MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.setHeader("Cache-Control","no-store");
            json.writeValue(response.getOutputStream(), ApiResponse.failure("40300","此接口仅供内部服务使用",null)); return;
        }
        chain.doFilter(request,response);
    }
}
