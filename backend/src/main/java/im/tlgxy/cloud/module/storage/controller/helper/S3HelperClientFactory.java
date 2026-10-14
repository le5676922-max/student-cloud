package im.tlgxy.cloud.module.storage.controller.helper;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.net.URI;

/** Request-scoped clients: close by try-with-resources, do not retain supplied secrets. */
@Component
public class S3HelperClientFactory {

    public S3Client create(S3HelperEndpointPolicy.ValidatedEndpoint endpoint, String region,
                           String accessKey, String secretKey) {
        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(endpoint.uri())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                // Path style prevents a bucket name from selecting a different host/DNS record.
                .forcePathStyle(true)
                .crossRegionAccessEnabled(false)
                // Apache SDK transport disables HTTP redirect handling; the pinned resolver also
                // rejects any host other than the exact validated endpoint. Never inherit proxies.
                .httpClientBuilder(ApacheHttpClient.builder()
                        .dnsResolver(endpoint.pinnedResolver())
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false).useEnvironmentVariableValues(false).build())
                        .connectionTimeout(Duration.ofSeconds(3))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(8))
                        .maxConnections(1))
                .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none())
                        .addExecutionInterceptor(new ExecutionInterceptor() {
                            @Override
                            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                                requireSameEndpoint(endpoint.uri(), context.httpRequest());
                            }
                        }))
                .build();
    }

    static void requireSameEndpoint(URI endpoint, SdkHttpRequest request) {
        int expectedPort = endpoint.getPort() >= 0 ? endpoint.getPort()
                : ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80);
        if (!endpoint.getHost().replace("[", "").replace("]", "")
                .equalsIgnoreCase(request.host().replace("[", "").replace("]", ""))
                || !endpoint.getScheme().equalsIgnoreCase(request.protocol())
                || expectedPort != request.port()) {
            throw new IllegalStateException("S3 helper endpoint changes are forbidden");
        }
    }
}
