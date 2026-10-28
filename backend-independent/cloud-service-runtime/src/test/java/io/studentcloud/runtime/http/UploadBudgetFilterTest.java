package io.studentcloud.runtime.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class UploadBudgetFilterTest {
    @Test void putAndPostUploadsShareTheSamePreMultipartBudget() throws Exception {
        var filter=new UploadBudgetFilter(new ObjectMapper());
        var first=new MockHttpServletRequest("PUT","/api/drives/check-drive/files");
        var second=new MockHttpServletRequest("POST","/api/courses/check-course/materials");
        var third=new MockHttpServletRequest("PUT","/api/drives/check-drive/files");
        var denied=new MockHttpServletResponse();
        filter.doFilter(first,new MockHttpServletResponse(),(one,outOne)->
            filter.doFilter(second,new MockHttpServletResponse(),(two,outTwo)->
                filter.doFilter(third,denied,(three,outThree)->fail("A third body must not be parsed"))));
        assertEquals(429,denied.getStatus());
        assertEquals("3",denied.getHeader("Retry-After"));
        var available=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("PUT","/api/drives/check-drive/files"),available,(request,response)->response.getWriter().write("ok"));
        assertEquals("ok",available.getContentAsString());
    }
}
