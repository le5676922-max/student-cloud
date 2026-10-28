package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bound manifests, including ignored JSON properties, before deserialization. */
@Component @Order(12)
public final class ResumableMetadataBodyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY=32768;
    private final Semaphore requests=new Semaphore(4);
    private final ObjectMapper json;
    public ResumableMetadataBodyFilter(ObjectMapper json){this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !"POST".equals(request.getMethod())||!java.util.Set.of("/api/uploads/resumable/init","/api/uploads/resumable/capabilities").contains(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(!requests.tryAcquire()){reject(response,429,"上传任务创建繁忙，请稍后重试");return;}
        try{
            if(request.getContentLengthLong()>MAX_BODY){reject(response,413,"上传清单请求不能超过32KiB");return;}
            byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
            if(bytes.length>MAX_BODY){reject(response,413,"上传清单请求不能超过32KiB");return;}
            chain.doFilter(new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                    @Override public int read(){return input.read();}
                    @Override public int read(byte[] buffer,int offset,int length){return input.read(buffer,offset,length);}
                    @Override public boolean isFinished(){return input.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(ReadListener listener){throw new IllegalStateException("Upload manifests use bounded synchronous reads");}
                };}
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            },response);
        }finally{requests.release();}
    }
    private void reject(HttpServletResponse response,int status,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getOutputStream(),ApiResponse.failure("UPLOAD_REQUEST_LIMIT",message,null));
    }
}
