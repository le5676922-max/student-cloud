package io.studentcloud.platform.http;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class WorkspaceSummaryController {
    private final AccountDirectory accounts;private final JdbcTemplate jdbc;private final RestClient client;private final CircuitBreakerFactory<?,?> breakers;
    public WorkspaceSummaryController(AccountDirectory accounts,JdbcTemplate jdbc,@Qualifier("serviceRestClientBuilder") RestClient.Builder builder,CircuitBreakerFactory<?,?> breakers){
        this.accounts=accounts;this.jdbc=jdbc;this.client=builder.build();this.breakers=breakers;
    }
    @GetMapping("/api/workspace-admin/summary") public ApiResponse<Map<String,Object>> summary(HttpServletRequest request){
        var actor=accounts.current(AuthenticationFilter.actor(request));
        if(actor.role()!=Role.TENANT_ADMIN || actor.workspaceId()==null) throw new ApplicationFault("30000",403,"只有本空间普通管理员可以查看此信息");
        var data=new LinkedHashMap<String,Object>();
        data.put("tenantName",jdbc.queryForObject("SELECT name FROM sc_workspace WHERE id=?",String.class,actor.workspaceId()));
        data.put("memberCount",jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE workspace_id=? AND role='USER'",Long.class,actor.workspaceId()));
        data.put("enabledMemberCount",jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE workspace_id=? AND role='USER' AND enabled=TRUE",Long.class,actor.workspaceId()));
        Long count=breakers.create("content-summary").run(()->{
            var response=client.get().uri("http://cloud-content/internal/content/workspaces/{id}/storage-count",actor.workspaceId()).retrieve()
                    .toEntity(new ParameterizedTypeReference<ApiResponse<Long>>(){});
            if(response.getBody()==null || !"0".equals(response.getBody().code()) || response.getBody().data()==null || response.getBody().data()<0)
                throw new IllegalStateException("Content summary unavailable");
            return response.getBody().data();
        },failure->null);
        data.put("storageCount",count);data.put("storageCountAvailable",count!=null);
        return ApiResponse.success(data);
    }
}
