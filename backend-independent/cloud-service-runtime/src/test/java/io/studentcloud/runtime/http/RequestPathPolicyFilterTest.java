package io.studentcloud.runtime.http;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class RequestPathPolicyFilterTest {
 @Test void matrixAndEncodedSeparatorsCannotBypassAnUploadBudget()throws Exception{
  var filter=new RequestPathPolicyFilter();
  for(String path:new String[]{"/api/drives/test/files;ignored=1","/api/drives/test/files%3Bignored=1","/api/uploads/resumable/abc%2Fchunks/0","/api/%73hares","/api/uploads/resumable/%69nit","/api/uploads/resumable/abc/chunks/%30"}){
   var request=new MockHttpServletRequest("PUT",path);var response=new MockHttpServletResponse();
   filter.doFilter(request,response,(a,b)->fail("Must reject before upload parser"));assertEquals(400,response.getStatus());
  }
 }
 @Test void virtualFileNamesRemainAllowedInTheQueryString()throws Exception{
  var request=new MockHttpServletRequest("GET","/api/drives/test/content");request.setQueryString("path=%2Fname%3Bpart.txt");
  var response=new MockHttpServletResponse();new RequestPathPolicyFilter().doFilter(request,response,(a,b)->response.setStatus(204));assertEquals(204,response.getStatus());
 }
}
