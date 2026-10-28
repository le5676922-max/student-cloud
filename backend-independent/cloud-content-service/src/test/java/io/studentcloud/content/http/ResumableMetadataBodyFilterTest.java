package io.studentcloud.content.http;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ResumableMetadataBodyFilterTest {
    @Test void boundsUnknownLengthJsonBeforeDeserialization()throws Exception{
        var filter=new ResumableMetadataBodyFilter(new ObjectMapper());
        var request=new MockHttpServletRequest("POST","/api/uploads/resumable/init"){
            @Override public long getContentLengthLong(){return -1;}
        };
        request.setContent(new byte[32769]);var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter.doFilter(request,response,(req,res)->called.set(true));
        assertEquals(413,response.getStatus());assertFalse(called.get());
    }
    @Test void preservesAcceptedJsonAndDoesNotBufferChunkStreams()throws Exception{
        var filter=new ResumableMetadataBodyFilter(new ObjectMapper());
        var request=new MockHttpServletRequest("POST","/api/uploads/resumable/init");request.setContent("{}".getBytes());
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertArrayEquals("{}".getBytes(),req.getInputStream().readAllBytes()));
        var raw=new MockHttpServletRequest("PUT","/api/uploads/resumable/id/chunks/0");
        filter.doFilter(raw,new MockHttpServletResponse(),(req,res)->assertSame(raw,req));
    }
}
