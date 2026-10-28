package io.studentcloud.intelligence.config;

import io.studentcloud.account.Actor;
import io.studentcloud.assistant.CourseKnowledgeAccess;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.common.ApiResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Authorization only: no content database dependency, private course data or fail-open fallback. */
@Component
public final class CourseKnowledgeClient implements CourseKnowledgeAccess {
    private final RestClient client;
    private final String internalKey;
    private final CircuitBreakerFactory<?,?> circuitBreakers;

    public CourseKnowledgeClient(@Qualifier("serviceRestClientBuilder") RestClient.Builder builder,
            @Value("${student-cloud.services.internal-key}") String internalKey, CircuitBreakerFactory<?,?> circuitBreakers) {
        this.client = builder.clone().baseUrl("http://cloud-content").build();
        this.internalKey = internalKey;
        this.circuitBreakers = circuitBreakers;
    }

    @Override public void requireRead(Actor actor, long courseId) {
        if (actor == null || actor.id() < 1 || courseId < 1) throw new ApplicationFault("40300", 403, "没有课程知识访问权限");
        try {
            ResponseEntity<ApiResponse<Void>> response = circuitBreakers.create("content-course-authorization").run(() -> client.get()
                    .uri("/internal/content/courses/{id}/read?accountId={accountId}", courseId, actor.id())
                    .header("X-StudentCloud-Service-Key", internalKey).retrieve()
                    // Expected authorization denials must not trip a circuit shared by unrelated users.
                    .onStatus(status -> status.is4xxClientError(), (request, result) -> { })
                    .toEntity(new ParameterizedTypeReference<ApiResponse<Void>>() { }), failure -> { throw unavailable(); });
            int code = response.getStatusCode().value();
            if (code == 401 || code == 403 || code == 404)
                throw new ApplicationFault("40300", 403, "课程不存在或当前没有访问权限");
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || !"0".equals(response.getBody().code())) throw unavailable();
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404)
                throw new ApplicationFault("40300", 403, "课程不存在或当前没有访问权限");
            throw unavailable();
        } catch (RestClientException unavailable) { throw unavailable(); }
    }

    private static ApplicationFault unavailable() {
        return new ApplicationFault("CONTENT_UNAVAILABLE", 503, "课程权限服务暂不可用，请稍后重试");
    }
}
