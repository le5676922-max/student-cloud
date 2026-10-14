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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void permitsResumableUploadHeadersOnlyForConfiguredOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/uploads/resumable/test/chunks/0");
        request.addHeader("Origin", "http://127.0.0.1:5173");
        request.addHeader("Access-Control-Request-Method", "PUT");
        request.addHeader("Access-Control-Request-Headers", "X-Upload-Directory-Password,X-Upload-Chunk-SHA256");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("http://127.0.0.1:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("X-Upload-Directory-Password"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("X-Upload-Chunk-SHA256"));
        assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("PUT"));
        assertNull(chain.getRequest());
    }

    private CorsFilter filterFor(String... origins) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(origins));
        return new CorsFilter(properties);
    }
}
