package im.tlgxy.cloud.core.config.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentReadinessFilterTest {
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final DeploymentReadinessFilter filter = new DeploymentReadinessFilter(availability, dataSource);

    @Test void localReadyClosesConnectionAndReturnsNoSensitiveDetails() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        var response = send("GET", "127.0.0.1");
        assertEquals(200, response.getStatus());
        assertEquals("{\"status\":\"UP\"}", response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(connection).close();
    }

    @Test void databaseFailureIsUnreadyAndDoesNotLeakException() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(dataSource.getConnection()).thenThrow(new SQLException("password=must-not-leak"));
        var response = send("GET", "::1");
        assertEquals(503, response.getStatus());
        assertEquals("{\"status\":\"DOWN\"}", response.getContentAsString());
    }

    @Test void startingAndStoppingDoNotObtainDatabaseConnection() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertEquals(503, send("GET", "127.0.0.1").getStatus());
        verifyNoInteractions(dataSource);
    }

    @Test void remotePeerCannotForgeLoopbackWithForwardingHeaders() throws Exception {
        var request = request("GET", "10.77.0.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Real-IP", "127.0.0.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> fail("Private probe must not reach MVC"));
        assertEquals(404, response.getStatus());
        verifyNoInteractions(dataSource, availability);
    }

    @Test void nonGetIsNotAHealthCheck() throws Exception {
        assertEquals(404, send("POST", "127.0.0.1").getStatus());
        verifyNoInteractions(dataSource, availability);
    }

    @Test void otherBusinessPathsContinueThroughNormalAuthentication() throws Exception {
        var request = request("GET", "127.0.0.1");
        request.setServletPath("/api/storage/list");
        boolean[] continued = {false};
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> continued[0] = true);
        assertTrue(continued[0]);
        verifyNoInteractions(dataSource, availability);
    }

    private MockHttpServletResponse send(String verb, String remote) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(verb, remote), response, (r, s) -> fail("Private probe must not reach MVC"));
        return response;
    }
    private MockHttpServletRequest request(String verb, String remote) {
        var request = new MockHttpServletRequest(verb, "/internal/ready");
        request.setServletPath("/internal/ready");
        request.setRemoteAddr(remote);
        return request;
    }
}
