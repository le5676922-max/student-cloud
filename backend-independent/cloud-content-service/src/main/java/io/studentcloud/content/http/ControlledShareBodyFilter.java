package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bound JSON before deserialization, including requests with no Content-Length. */
@Component
@Order(11)
public final class ControlledShareBodyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY=8192;
    private final Semaphore requests=new Semaphore(8);
    private final ObjectMapper json;
    public ControlledShareBodyFilter(ObjectMapper json){this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !"POST".equals(request.getMethod())||!"/api/shares".equals(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(!requests.tryAcquire()){reject(response,429,"SHARE_BUSY","分享创建繁忙，请稍后重试");return;}
        try{
            if(request.getContentLengthLong()>MAX_BODY){reject(response,413,"SHARE_BODY_LIMIT","分享请求不能超过8KiB");return;}
            byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
            if(bytes.length>MAX_BODY){reject(response,413,"SHARE_BODY_LIMIT","分享请求不能超过8KiB");return;}
            chain.doFilter(new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){
                    var input=new ByteArrayInputStream(bytes);
                    return new ServletInputStream(){
                        @Override public int read(){return input.read();}
                        @Override public int read(byte[] buffer,int offset,int length){return input.read(buffer,offset,length);}
                        @Override public boolean isFinished(){return input.available()==0;}
                        @Override public boolean isReady(){return true;}
                        @Override public void setReadListener(ReadListener listener){throw new IllegalStateException("Share JSON uses synchronous bounded reads");}
                    };
                }
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            },response);
        }finally{requests.release();}
    }
    private void reject(HttpServletResponse response,int status,String code,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getOutputStream(),ApiResponse.failure(code,message,UUID.randomUUID().toString()));
    }
}
