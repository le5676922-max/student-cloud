package io.studentcloud.clients;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.SessionAuthenticator;
import io.studentcloud.account.WorkspaceDirectory;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** 4xx are business denials, not dependency outages; neither is converted into an authenticated fallback. */
public final class RemoteIdentityClient implements AccountDirectory,SessionAuthenticator,WorkspaceDirectory {
    private final RestClient http;
    private final CircuitBreakerFactory<?,?> breakers;
    RemoteIdentityClient(RestClient http,CircuitBreakerFactory<?,?> breakers) { this.http=http;this.breakers=breakers; }

    @Override public Actor authenticate(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApplicationFault("42000",401,"请先登录");
        return fetch(() -> http.post().uri("http://cloud-platform/internal/identity/session").body(Map.of("token",token)).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,(request,response)->{}).toEntity(new ParameterizedTypeReference<ApiResponse<Actor>>(){}));
    }
    @Override public Actor account(long accountId) {
        if(accountId<1) throw new ApplicationFault("42000",401,"账号不可用");
        return fetch(() -> http.get().uri("http://cloud-platform/internal/identity/accounts/{id}",accountId).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,(request,response)->{}).toEntity(new ParameterizedTypeReference<ApiResponse<Actor>>(){}));
    }
    @Override public boolean workspaceEnabled(long id) {
        if(id<1) return false;
        return fetch(() -> http.get().uri("http://cloud-platform/internal/identity/workspaces/{id}/enabled",id).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,(request,response)->{}).toEntity(new ParameterizedTypeReference<ApiResponse<Boolean>>(){}));
    }
    @Override public java.util.List<Actor> administrators(long id){
        if(id<1)throw new ApplicationFault("WORKSPACE_INVALID",400,"工作空间无效");
        java.util.List<Actor> actors=fetch(()->http.get().uri("http://cloud-platform/internal/identity/workspaces/{id}/administrators",id).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,(request,response)->{}).toEntity(new ParameterizedTypeReference<ApiResponse<java.util.List<Actor>>>(){}));
        if(actors.size()>100||actors.stream().anyMatch(a->a.role()!=io.studentcloud.account.Role.TENANT_ADMIN||!java.util.Objects.equals(a.workspaceId(),id)))throw unavailable();
        return java.util.List.copyOf(actors);
    }
    private <T> T fetch(Supplier<ResponseEntity<ApiResponse<T>>> request) {
        ResponseEntity<ApiResponse<T>> response=breakers.create("identity").run(request,failed->{throw unavailable();});
        int status=response.getStatusCode().value();
        if(status>=400 && status<500) {
            if(status==401) throw new ApplicationFault("42000",401,"账号或会话已失效，请重新登录");
            if(status==403 || status==404) throw new ApplicationFault("40300",403,"没有访问权限");
            throw new ApplicationFault("IDENTITY_REQUEST_REJECTED",status,"身份服务暂未接受此操作");
        }
        ApiResponse<T> body=response.getBody();
        if(!response.getStatusCode().is2xxSuccessful() || body==null || !"0".equals(body.code()) || body.data()==null) throw unavailable();
        return body.data();
    }
    private static ApplicationFault unavailable() {return new ApplicationFault("IDENTITY_UNAVAILABLE",503,"身份服务暂不可用，请稍后重试");}
}
