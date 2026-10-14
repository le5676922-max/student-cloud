package im.tlgxy.cloud.core.config.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * Private Docker readiness probe, not a new public authentication bypass.
 * The directly connected peer must be loopback; forwarded headers are never trusted here.
 * Runs before MVC/Sa-Token, answers only this exact path and exposes no database/configuration details.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DeploymentReadinessFilter extends OncePerRequestFilter {
    private static final String PATH = "/internal/ready";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final ApplicationAvailability availability;
    private final DataSource dataSource;

    public DeploymentReadinessFilter(ApplicationAvailability availability, DataSource dataSource) {
        this.availability = availability;
        this.dataSource = dataSource;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) || !LOOPBACK.contains(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        boolean ready = false;
        if (availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC) {
            try (Connection connection = dataSource.getConnection()) {
                ready = connection.isValid(2);
            } catch (SQLException ignored) {
                // Health responses and logs must not reveal JDBC addresses or passwords.
            }
        }
        response.setContentType("application/json");
        response.setStatus(ready ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write(ready ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
    }
}
