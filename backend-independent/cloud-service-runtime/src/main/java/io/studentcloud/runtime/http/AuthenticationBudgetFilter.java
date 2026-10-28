package io.studentcloud.runtime.http;

import tools.jackson.databind.ObjectMapper;
import io.studentcloud.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded single-instance protection during development; not a distributed rate limiter. */
@Component
@Order(5)
public class AuthenticationBudgetFilter extends OncePerRequestFilter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final Set<String> EXPENSIVE = Set.of("/user/login", "/api/install", "/user/updatePwd", "/user/register", "/user/password-reset");
    private final Semaphore hashingSlots = new Semaphore(3);
    private final Map<String, Bucket> attempts = new HashMap<>();
    private final ObjectMapper json;
    private final TrustedClientAddress clients;

    public AuthenticationBudgetFilter(ObjectMapper json,TrustedClientAddress clients) { this.json = json; this.clients=clients; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !EXPENSIVE.contains(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // A signed gateway context keeps unrelated users from sharing one proxy-address budget.
        if (!allow(clients.peer(request)) || !hashingSlots.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ApiResponse.failure("42900", "请求过于频繁，请稍后重试", null));
            return;
        }
        try { chain.doFilter(request, response); }
        finally { hashingSlots.release(); }
    }

    private synchronized boolean allow(String peer) {
        long now = System.nanoTime();
        if (attempts.size() >= 1024) attempts.entrySet().removeIf(entry -> now - entry.getValue().started() >= WINDOW_NANOS);
        Bucket current = attempts.get(peer);
        if (current == null || now - current.started() >= WINDOW_NANOS) {
            if (current == null && attempts.size() >= 1024) return false;
            attempts.put(peer, new Bucket(now, 1));
            return true;
        }
        if (current.count() >= 30) return false;
        attempts.put(peer, new Bucket(current.started(), current.count() + 1));
        return true;
    }
    private record Bucket(long started, int count) { }
}
