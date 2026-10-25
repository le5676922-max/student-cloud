package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.controller.helper.S3HelperClientFactory;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperEndpointPolicy;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageProbeWritesTest {
    @TempDir Path directory;
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final S3HelperEndpointPolicy policy = mock(S3HelperEndpointPolicy.class);
    private final S3HelperClientFactory factory = mock(S3HelperClientFactory.class);
    private final S3Client client = mock(S3Client.class);
    private final StorageSourceSafetyService service = new StorageSourceSafetyService(security, tenants, mock(StorageSourceConfigService.class), policy, factory);
    private final AtomicReference<PutObjectRequest> written = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private User actor;
    @BeforeEach void setup() throws Exception {
        actor = new User(); actor.setRole(UserRoleEnum.ADMIN); actor.setId(1);
        when(security.requireStorageAdministrator()).thenReturn(actor);
        when(policy.validate(anyString())).thenReturn(new S3HelperEndpointPolicy.ValidatedEndpoint(URI.create("https://objects.example.test"),new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})}));
        when(factory.create(any(),anyString(),anyString(),anyString())).thenReturn(client);
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call -> {
            written.set(call.getArgument(0)); body.set(((RequestBody)call.getArgument(1)).contentStreamProvider().newStream().readAllBytes());
            return PutObjectResponse.builder().eTag("test-etag").versionId("test-version").build();
        });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(body.get())));
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> HeadObjectResponse.builder()
                .metadata(written.get().metadata()).contentLength((long)body.get().length).eTag("test-etag").build());
    }
    @AfterEach void close() { service.close(); }
    private SaveStorageSourceRequest request(StorageTypeEnum type) {
        var request = new SaveStorageSourceRequest(); request.setType(type); request.setVerifyWrite(true);
        var p = new StorageSourceAllParamDTO(); p.setBucketName("test-bucket"); p.setBasePath("/course"); p.setRegion("test");
        p.setEndPoint("https://objects.example.test"); p.setAccessKey("fake"); p.setSecretKey("fake"); p.setFilePath(directory.toString());
        request.setStorageSourceAllParam(p); return request;
    }
    @Test void successfulS3ProbeIsUniqueBoundedVersionedAndConfirmedDeleted() {
        doAnswer(call -> {
            when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
            return DeleteObjectResponse.builder().build();
        }).when(client).deleteObject(any(DeleteObjectRequest.class));
        var result = service.testConnection(request(StorageTypeEnum.S3));
        assertTrue(result.passed()); assertTrue(result.readable()); assertTrue(result.writable()); assertTrue(result.cleaned()); assertNull(result.probeKey());
        assertTrue(written.get().key().startsWith("course/.student-cloud-probe-")); assertTrue(body.get().length < 128);
        assertEquals(List.of("*"),written.get().overrideConfiguration().orElseThrow().headers().get("If-None-Match"));
        verify(client).deleteObject(argThat((DeleteObjectRequest deletion) -> "test-version".equals(deletion.versionId()) && deletion.key().equals(written.get().key())));
    }
    @Test void successfulWriteButDeniedCleanupNeverReportsFullSuccess() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());
        var result = service.testConnection(request(StorageTypeEnum.S3));
        assertFalse(result.passed()); assertTrue(result.readable()); assertTrue(result.writable()); assertFalse(result.cleaned()); assertNotNull(result.probeKey());
        assertFalse(result.message().contains("fake"));
    }
    @Test void replacedObjectIsNeverDeletedWhenOwnershipMetadataDiffers() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().metadata(Map.of("student-cloud-probe","somebody-else")).contentLength(65L).build());
        assertFalse(service.testConnection(request(StorageTypeEnum.S3)).passed());
        verify(client,never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void corruptReadbackStillCleansOnlyOwnProbeAndRemainsFailure() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),new ByteArrayInputStream(new byte[]{1,2,3})));
        doAnswer(call -> { when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build()); return DeleteObjectResponse.builder().build(); }).when(client).deleteObject(any(DeleteObjectRequest.class));
        var result = service.testConnection(request(StorageTypeEnum.S3));
        assertFalse(result.passed()); assertFalse(result.writable()); assertTrue(result.cleaned());
    }
    @Test void localProbePreservesUserFilesAndTenantManagerCannotTestServerPaths() throws Exception {
        Path existing = directory.resolve("real-user-data.txt"); Files.writeString(existing,"preserve");
        var result = service.testConnection(request(StorageTypeEnum.LOCAL));
        assertTrue(result.passed()); assertTrue(result.writable()); assertTrue(result.cleaned());
        assertEquals("preserve", Files.readString(existing)); try(var files = Files.list(directory)) { assertEquals(1,files.count()); }
        actor.setRole(UserRoleEnum.TENANT_ADMIN); actor.setTenantId(9);
        assertThrows(im.tlgxy.cloud.core.exception.core.BizException.class,()->service.testConnection(request(StorageTypeEnum.LOCAL)));
    }
}
