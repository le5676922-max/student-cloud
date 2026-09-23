package im.tlgxy.cloud.module.ai.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiAttachmentSizeFilterTest {

    private final AiAttachmentSizeFilter filter = new AiAttachmentSizeFilter();

    @Test
    void rejectsUnknownAndOversizedAiMultipartRequestsBeforeParsing() throws Exception {
        MockHttpServletRequest unknownLength = aiUploadRequest(-1);
        MockHttpServletResponse unknownLengthResponse = new MockHttpServletResponse();
        MockFilterChain unknownLengthChain = new MockFilterChain();

        filter.doFilter(unknownLength, unknownLengthResponse, unknownLengthChain);

        assertEquals(413, unknownLengthResponse.getStatus());
        assertNull(unknownLengthChain.getRequest());

        MockHttpServletRequest oversized = aiUploadRequest(AiAttachmentSizeFilter.MAX_REQUEST_BYTES + 1);
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
        MockFilterChain oversizedChain = new MockFilterChain();

        filter.doFilter(oversized, oversizedResponse, oversizedChain);

        assertEquals(413, oversizedResponse.getStatus());
        assertNull(oversizedChain.getRequest());
    }

    @Test
    void letsBoundedAiMultipartRequestsReachSpring() throws Exception {
        MockHttpServletRequest request = aiUploadRequest(AiAttachmentSizeFilter.MAX_REQUEST_BYTES);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest aiUploadRequest(long contentLength) {
        return new MockHttpServletRequest("POST", "/api/ai/chat/files") {
            @Override
            public long getContentLengthLong() {
                return contentLength;
            }
        };
    }
}
