package io.studentcloud.intelligence.agent;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/** The content service alone owns filesystem authorization and idempotent writes. */
@Component
public final class AgentContentClient {
    private final RestClient client;
    private final String key;
    public AgentContentClient(@Qualifier("serviceRestClientBuilder") RestClient.Builder builder,
            @Value("${student-cloud.services.internal-key}") String key) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = builder.clone().requestFactory(factory).baseUrl("http://cloud-content").build(); this.key = key;
    }

    public Preview preview(Actor actor, String storageKey, List<Step> steps) {
        try {
            var result = client.post().uri("/internal/content/agent/preview")
                    .header("X-StudentCloud-Service-Key", key)
                    .body(payload(actor.id(), actor.workspaceId(), "storageKey", storageKey, "steps", steps))
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Preview>>() { });
            if (result == null || !"0".equals(result.code()) || result.data() == null) throw unavailable();
            return result.data();
        } catch (org.springframework.web.client.RestClientResponseException failure) { throw business(failure); }
        catch (RestClientException failure) { throw unavailable(); }
    }

    public Preview previewBulk(Actor actor, String storageKey, String path, String type) {
        return previewBulk(actor,storageKey,path,type,null,null);
    }
    public Preview previewBulk(Actor actor, String storageKey, String path, String type, Long courseId, String tags) {
        try {
            var body=payload(actor.id(), actor.workspaceId(), "storageKey", storageKey, "type", type, "path", path);
            body.put("courseId",courseId);body.put("tags",tags);
            var result = client.post().uri("/internal/content/agent/preview")
                    .header("X-StudentCloud-Service-Key", key)
                    .body(body)
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Preview>>() { });
            if (result == null || !"0".equals(result.code()) || result.data() == null) throw unavailable();
            return result.data();
        } catch (org.springframework.web.client.RestClientResponseException failure) { throw business(failure); }
        catch (RestClientException failure) { throw unavailable(); }
    }

    public Execution execute(long actorId, Long workspaceId, String previewToken, String idempotencyKey) {
        try {
            var result = client.post().uri("/internal/content/agent/execute")
                    .header("X-StudentCloud-Service-Key", key)
                    .body(payload(actorId, workspaceId, "previewToken", previewToken, "idempotencyKey", idempotencyKey))
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Execution>>() { });
            if (result == null || !"0".equals(result.code()) || result.data() == null) throw unavailable();
            return result.data();
        } catch (org.springframework.web.client.RestClientResponseException failure) { throw business(failure); }
        catch (RestClientException failure) { throw unavailable(); }
    }

    public record Step(String op, String sourcePath, String targetPath,Long courseId,String tags) {
        public Step(String op,String sourcePath,String targetPath){this(op,sourcePath,targetPath,null,null);}
    }
    public record Preview(String previewToken, long expiresAt, List<Step> steps) { }
    public record DuplicateGroup(long size,List<String> paths){}
    public record DuplicateReport(int checkedFiles,int skippedFiles,List<DuplicateGroup> groups,int attemptedFiles){}
    public DuplicateReport duplicates(Actor actor,String storageKey,String path){
        try{
            var result=client.post().uri("/internal/content/agent/duplicates").header("X-StudentCloud-Service-Key",key)
                .body(payload(actor.id(),actor.workspaceId(),"storageKey",storageKey,"path",path))
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<DuplicateReport>>(){});
            if(result==null || !"0".equals(result.code()) || result.data()==null)throw unavailable();
            return result.data();
        }catch(org.springframework.web.client.RestClientResponseException failure){throw business(failure);}
        catch(RestClientException failure){throw unavailable();}
    }
    public record StepResult(int index, String op, boolean success, String message) { }
    public record Execution(String status, List<StepResult> results, boolean replayed) { }
    private static Map<String,Object> payload(long actorId, Long workspaceId, String key1, Object value1, String key2, Object value2) {
        var body = new LinkedHashMap<String,Object>();
        body.put("actorId", actorId); body.put("workspaceId", workspaceId);
        body.put(key1, value1); body.put(key2, value2); return body;
    }
    private static Map<String,Object> payload(long actorId, Long workspaceId, String key1, Object value1,
            String key2, Object value2, String key3, Object value3) {
        var body = payload(actorId, workspaceId, key1, value1, key2, value2);
        body.put(key3, value3); return body;
    }
    private static ApplicationFault unavailable() { return new ApplicationFault("CONTENT_UNAVAILABLE", 503, "文件服务暂不可用，请稍后重试"); }
    private static ApplicationFault business(org.springframework.web.client.RestClientResponseException failure){
        int code=failure.getStatusCode().value();
        if(code==400 || code==403 || code==404 || code==409 || code==429)
            return new ApplicationFault("AGENT_CONTENT_REJECTED",code,"文件操作未获准或状态已变化，请刷新目录后重新预览");
        return unavailable();
    }
}
