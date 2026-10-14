package im.tlgxy.cloud.module.storage.controller.helper;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

import java.net.URI;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class S3HelperClientFactoryTest {

    private final URI endpoint = URI.create("https://objects.example.com");

    @Test
    void buildsAndClosesConfiguredTransportWithoutAnyProviderRequest() throws Exception {
        var validated = new S3HelperEndpointPolicy.ValidatedEndpoint(endpoint,
                new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        try (var client = new S3HelperClientFactory().create(validated, "us-east-1", "synthetic-access", "synthetic-secret")) {
            assertNotNull(client);
        }
    }

    @Test
    void allowsS3PathsAndQueriesAtTheExactConfiguredEndpoint() {
        var request = request("https", "objects.example.com", 443)
                .encodedPath("/synthetic-bucket").putRawQueryParameter("cors", "").build();
        assertDoesNotThrow(() -> S3HelperClientFactory.requireSameEndpoint(endpoint, request));
    }

    @Test
    void rejectsDifferentHostIncludingNumericRedirectTargetBeforeTransmission() {
        var request = request("https", "169.254.169.254", 443).build();
        assertThrows(IllegalStateException.class, () -> S3HelperClientFactory.requireSameEndpoint(endpoint, request));
    }

    @Test
    void rejectsProtocolDowngrade() {
        var request = request("http", "objects.example.com", 80).build();
        assertThrows(IllegalStateException.class, () -> S3HelperClientFactory.requireSameEndpoint(endpoint, request));
    }

    @Test
    void rejectsChangedPort() {
        var request = request("https", "objects.example.com", 9000).build();
        assertThrows(IllegalStateException.class, () -> S3HelperClientFactory.requireSameEndpoint(endpoint, request));
    }

    private SdkHttpRequest.Builder request(String protocol, String host, int port) {
        return SdkHttpRequest.builder().protocol(protocol).host(host).port(port).method(SdkHttpMethod.GET);
    }
}
