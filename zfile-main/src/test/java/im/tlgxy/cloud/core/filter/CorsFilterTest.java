package im.tlgxy.cloud.core.filter;

import im.tlgxy.cloud.core.config.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorsFilterTest {

    private final CorsFilter filter = filterFor("http://127.0.0.1:5173");

    @Test
    void allowsConfiguredOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/site/config/global");
        request.addHeader("Origin", "http://127.0.0.1:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("http://127.0.0.1:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsPreflightFromUnknownOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/ai/chat");
        request.addHeader("Origin", "https://untrusted.example");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    private CorsFilter filterFor(String... origins) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(origins));
        return new CorsFilter(properties);
    }
}
