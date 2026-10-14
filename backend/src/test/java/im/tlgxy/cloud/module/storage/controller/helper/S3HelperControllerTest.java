package im.tlgxy.cloud.module.storage.controller.helper;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.GetS3BucketListRequest;
import im.tlgxy.cloud.module.storage.model.request.GetS3CorsListRequest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.InetAddress;
import java.time.Instant;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3HelperControllerTest {

    private final S3HelperClientFactory factory = mock(S3HelperClientFactory.class);
    private final S3Client client = mock(S3Client.class);

    private S3HelperController publicController() {
        S3HelperEndpointPolicy policy = new S3HelperEndpointPolicy(host ->
                new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        when(factory.create(any(), anyString(), anyString(), anyString())).thenReturn(client);
        return new S3HelperController(policy, factory);
    }

    @Test
    void readsBucketsAndClosesClient() {
        when(client.listBuckets()).thenReturn(ListBucketsResponse.builder().buckets(Bucket.builder()
                .name("synthetic-bucket").creationDate(Instant.EPOCH).build()).build());
        assertEquals(1, publicController().getBucketNames(bucketRequest()).getData().size());
        verify(client).close();
        verify(factory).create(any(), eq("us-east-1"), eq("synthetic-access"), eq("synthetic-secret"));
    }

    @Test
    void rejectsInternalEndpointBeforeCreatingClient() throws Exception {
        S3HelperEndpointPolicy denied = new S3HelperEndpointPolicy(host ->
                new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})});
        assertThrows(BizException.class, () -> new S3HelperController(denied, factory).getBucketNames(bucketRequest()));
        verifyNoInteractions(factory, client);
    }

    @Test
    void closesClientOnFailureWithoutLeakingSdkMessages() {
        when(client.listBuckets()).thenThrow(new IllegalStateException("synthetic-secret Authorization sensitive"));
        BizException failure = assertThrows(BizException.class, () -> publicController().getBucketNames(bucketRequest()));
        assertFalse(failure.getMessage().contains("synthetic-secret"));
        verify(client).close();
    }

    @Test
    void rejectsInvalidBucketBeforeCreatingClient() {
        var request = corsRequest();
        request.setBucketName("../other-host");
        assertThrows(BizException.class, () -> publicController().getCorsConfig(request));
        verifyNoInteractions(client);
        verify(factory, never()).create(any(), anyString(), anyString(), anyString());
    }

    @Test
    void readsCorsAndClosesClient() {
        when(client.getBucketCors(any(Consumer.class))).thenReturn(GetBucketCorsResponse.builder().build());
        assertTrue(publicController().getCorsConfig(corsRequest()).getData().isEmpty());
        verify(client).close();
    }

    @Test
    void preservesEmptyCorsForMissingCorsConfiguration() {
        when(client.getBucketCors(any(Consumer.class))).thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        assertTrue(publicController().getCorsConfig(corsRequest()).getData().isEmpty());
        verify(client).close();
    }

    private GetS3BucketListRequest bucketRequest() {
        var request = new GetS3BucketListRequest();
        request.setEndPoint("https://objects.example.com");
        request.setAccessKey("synthetic-access");
        request.setSecretKey("synthetic-secret");
        return request;
    }

    private GetS3CorsListRequest corsRequest() {
        var request = new GetS3CorsListRequest();
        request.setEndPoint("https://objects.example.com");
        request.setAccessKey("synthetic-access");
        request.setSecretKey("synthetic-secret");
        request.setBucketName("synthetic-bucket");
        return request;
    }
}
