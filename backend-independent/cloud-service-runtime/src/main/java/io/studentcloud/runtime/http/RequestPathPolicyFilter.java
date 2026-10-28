package io.studentcloud.runtime.http;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** API route segments never contain matrix parameters; reject before routing, authentication or body parsing. */
@Component @Order(-100)
public final class RequestPathPolicyFilter extends OncePerRequestFilter {
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  // All route identifiers are canonical ASCII. File names and virtual paths belong in the query/body.
  // Reject percent-encoded route aliases too, so raw-URI body budgets cannot be bypassed by MVC decoding.
  String path=request.getRequestURI();
  if(path.indexOf(';')>=0||path.indexOf('%')>=0||path.indexOf('\\')>=0){
   response.setStatus(400);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"INVALID_ROUTE\",\"msg\":\"接口路径格式不正确\",\"data\":null}");return;
  }
  chain.doFilter(request,response);
 }
}
