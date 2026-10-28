package io.studentcloud.platform.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.TrustedClientAddress;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bounded mail requests before body parsing; verified gateway client context separates unrelated users. */
@Component @Order(4)
public final class PublicEmailBudgetFilter extends OncePerRequestFilter {
    private final Set<String> endpoints=Set.of("/user/registration/email-code","/user/password-reset/email-code");
    private final HashMap<String,Window> windows=new HashMap<>();
    private final Semaphore slots=new Semaphore(2);
    private final ObjectMapper json;private final TrustedClientAddress clients;
    public PublicEmailBudgetFilter(ObjectMapper json,TrustedClientAddress clients){this.json=json;this.clients=clients;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !"POST".equals(request.getMethod()) || !endpoints.contains(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(!allow(clients.peer(request)) || !slots.tryAcquire()){
            response.setStatus(429);response.setHeader("Retry-After","60");response.setHeader("Cache-Control","no-store");response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(),ApiResponse.failure("41005","邮件服务繁忙，请60秒后重试",null));return;
        }
        try{chain.doFilter(request,response);}finally{slots.release();}
    }
    private synchronized boolean allow(String peer){
        long now=System.nanoTime();
        windows.entrySet().removeIf(entry->now-entry.getValue().started>=60_000_000_000L);
        Window current=windows.get(peer);
        if(current==null){if(windows.size()>=1024)return false;windows.put(peer,new Window(now,1));return true;}
        if(current.count>=10)return false;windows.put(peer,new Window(current.started,current.count+1));return true;
    }
    private record Window(long started,int count){ }
}
