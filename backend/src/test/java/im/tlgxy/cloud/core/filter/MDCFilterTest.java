package im.tlgxy.cloud.core.filter;

import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.constant.MdcConstant;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MDCFilterTest {
    private final MDCFilter filter = new MDCFilter();

    @Test
    void requestsWithoutSessionReachMvcWithoutAFormerGuestIdentity() throws Exception {
        try (var tokens = mockStatic(StpUtil.class); var identities = mockStatic(TlgxyCloudAuthUtil.class)) {
            MDC.put(MdcConstant.USER, "previous-request-user");
            filter.doFilter(new MockHttpServletRequest("GET", "/api/install/status"), new MockHttpServletResponse(),
                    (request, response) -> {
                        assertNull(MDC.get(MdcConstant.USER));
                        assertNotNull(MDC.get(MdcConstant.TRACE_ID));
                    });
            assertNull(MDC.get(MdcConstant.TRACE_ID));
            assertNull(MDC.get(MdcConstant.USER));
            identities.verifyNoInteractions();
            tokens.verify(StpUtil::getLoginIdDefaultNull);
        }
    }

    @Test
    void recordsSessionActorAndClearsContextEvenWhenBusinessRequestFails() {
        try (var tokens = mockStatic(StpUtil.class)) {
            tokens.when(StpUtil::getLoginIdDefaultNull).thenReturn(6);
            assertThrows(IOException.class, () -> filter.doFilter(new MockHttpServletRequest("GET", "/api/private"),
                    new MockHttpServletResponse(), (request, response) -> {
                        assertEquals("6", MDC.get(MdcConstant.USER));
                        throw new IOException("synthetic business failure");
                    }));
            assertNull(MDC.get(MdcConstant.USER));
            assertNull(MDC.get(MdcConstant.TRACE_ID));
        }
    }
}
