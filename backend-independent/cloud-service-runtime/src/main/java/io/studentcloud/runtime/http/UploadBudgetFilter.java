package io.studentcloud.runtime.http;

import tools.jackson.databind.ObjectMapper;
import io.studentcloud.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Acquired before multipart parsing so concurrent request bodies cannot exhaust temporary storage. */
@Component
@Order(20)
public class UploadBudgetFilter extends OncePerRequestFilter {
    private final Semaphore uploads = new Semaphore(2);
    private final ObjectMapper json;
    public UploadBudgetFilter(ObjectMapper json) { this.json = json; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean bodyMethod="POST".equals(request.getMethod()) || "PUT".equals(request.getMethod());
        return !bodyMethod || !request.getRequestURI().matches("(?:/api/(?:uploads/resumable/[^/]+/chunks/[0-9]+|drives/[^/]+/files|courses/[^/]+/materials|ai/chat/files(?:/stream/[^/]+)?)|/internal/content/attachments/extract)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!uploads.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "3");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ApiResponse.failure("42900", "同时上传数量已达上限，请稍后重试", null));
            return;
        }
        try { chain.doFilter(request, response); }
        finally { uploads.release(); }
    }
}
