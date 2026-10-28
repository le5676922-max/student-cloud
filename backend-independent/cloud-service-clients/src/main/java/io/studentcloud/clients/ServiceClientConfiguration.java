package io.studentcloud.clients;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.SessionAuthenticator;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.util.concurrent.*;

/** Explicit clients; only a pure contract is shared with identity, never its persistence. */
@Configuration(proxyBeanMethods=false)
public class ServiceClientConfiguration {
    @Bean(destroyMethod="shutdownNow") ExecutorService serviceClientExecutor() {
        var threads=new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(2,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),runnable->{
            var thread=new Thread(runnable,"service-call-"+threads.incrementAndGet());thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean Customizer<Resilience4JCircuitBreakerFactory> boundedServiceCalls(@Qualifier("serviceClientExecutor") ExecutorService executor) {
        return factory->{
            factory.configureExecutorService(executor);
            factory.configureDefault(id->new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(CircuitBreakerConfig.custom().slidingWindowSize(20).minimumNumberOfCalls(10)
                            .waitDurationInOpenState(Duration.ofSeconds(10)).build())
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).cancelRunningFuture(true).build()).build());
        };
    }
    @Bean(name="serviceRestClientBuilder") @LoadBalanced
    RestClient.Builder serviceRestClientBuilder(@Value("${student-cloud.services.internal-key:}") String key) {
        if(key.length()<32 || key.length()>200 || key.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Configure a random internal service key of at least 32 characters");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(factory).defaultHeader("X-StudentCloud-Service-Key",key);
    }


}
