package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperClientFactory;
import im.tlgxy.cloud.module.storage.controller.helper.S3HelperEndpointPolicy;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.TlgxyCloudCORSRule;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageSourceSafetyServiceTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final StorageSourceConfigService configs = mock(StorageSourceConfigService.class);
    private final S3HelperEndpointPolicy policy = mock(S3HelperEndpointPolicy.class);
    private final S3HelperClientFactory factory = mock(S3HelperClientFactory.class);
    private final S3Client client = mock(S3Client.class);
    private final StorageSourceSafetyService service = new StorageSourceSafetyService(security, tenants, configs, policy, factory);
    @TempDir Path temporary;
    @AfterEach void close() { service.close(); }

    private SaveStorageSourceRequest request(StorageTypeEnum type) {
        SaveStorageSourceRequest result = new SaveStorageSourceRequest();
        result.setType(type);
        var params = new StorageSourceAllParamDTO();
        params.setEndPoint("objects.example.test"); params.setEndPointScheme("https");
        params.setAccessKey("synthetic-access"); params.setSecretKey("synthetic-secret");
        params.setBucketName("course-files"); params.setRegion("test-region"); params.setBasePath("/documents/");
        result.setStorageSourceAllParam(params);
        return result;
    }
    private void availableClient() throws Exception {
        var endpoint = new S3HelperEndpointPolicy.ValidatedEndpoint(URI.create("https://objects.example.test"),
                new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})});
        when(policy.validate(anyString())).thenReturn(endpoint);
        when(factory.create(any(), anyString(), anyString(), anyString())).thenReturn(client);
    }
    private void savedRules() {
        StorageSource source = new StorageSource(); source.setId(42); source.setType(StorageTypeEnum.S3);
        when(security.requireManageStorage(42)).thenReturn(source);
        List<StorageSourceConfig> values = new ArrayList<>();
        add(values, "endPoint", "objects.example.test"); add(values, "endPointScheme", "https");
        add(values, "region", "test-region"); add(values, "bucketName", "saved-bucket");
        add(values, "accessKey", "saved-synthetic-access"); add(values, "secretKey", "saved-synthetic-secret");
        add(values, "corsConfigList", "[{\"allowedMethods\":[\"PUT\"],\"allowedOrigins\":[\"https://cloud.example.test\"],\"allowedHeaders\":[\"*\"]}]");
        when(configs.selectFreshStorageConfigByStorageId(42)).thenReturn(values);
    }
    private static void add(List<StorageSourceConfig> values, String name, String value) {
        var config = new StorageSourceConfig(); config.setName(name); config.setValue(value); values.add(config);
    }

    @Test void localCheckIsReadOnlyAndNeverReadsConfigurationOrCreatesDirectory() throws Exception {
        var request = request(StorageTypeEnum.LOCAL); request.getStorageSourceAllParam().setFilePath(temporary.toString());
        assertTrue(service.testConnection(request).passed());
        verify(security).requireSystemAdmin(); verify(tenants).getEnabledById(1);
        try (var entries = Files.list(temporary)) { assertEquals(0, entries.count()); }
        verifyNoInteractions(configs, policy, factory, client);
    }
    @Test void missingLocalDirectoryDoesNotCreateIt() {
        var request = request(StorageTypeEnum.LOCAL); var missing = temporary.resolve("missing");
        request.getStorageSourceAllParam().setFilePath(missing.toString());
        assertThrows(BizException.class, () -> service.testConnection(request)); assertFalse(Files.exists(missing));
        verifyNoInteractions(configs, policy, factory);
    }
    @Test void localPreflightRejectsPrivateStagingExposureEvenWhenUploadsAreDisabled() throws Exception {
        var staging = Files.createDirectory(temporary.resolve("private-parts"));
        var properties = new ResumableUploadProperties(); properties.setEnabled(false); properties.setStagingRoot(staging.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resumableUploads", properties);
        for (Path directory : List.of(temporary, staging, staging.resolve("child"))) {
            var request = request(StorageTypeEnum.LOCAL); request.getStorageSourceAllParam().setFilePath(directory.toString());
            assertThrows(BizException.class, () -> service.testConnection(request));
        }
        assertFalse(Files.exists(staging.resolve("child")));
        verifyNoInteractions(configs, policy, factory);
    }
    @Test void relativeTraversalAndNetworkSharesAreRejectedWithoutProviderAccess() {
        for (var value : List.of("relative", temporary.resolve("..").toString(), "\\\\server\\share", "//server/share", "bad\npath")) {
            var request = request(StorageTypeEnum.LOCAL); request.getStorageSourceAllParam().setFilePath(value);
            assertThrows(BizException.class, () -> service.testConnection(request));
        }
        verifyNoInteractions(configs, factory, policy);
    }
    @Test void s3ProbeReadsOneObjectAtConfiguredPrefixAndNeverWritesOrSaves() throws Exception {
        availableClient(); var request = request(StorageTypeEnum.S3); request.setId(42);
        assertTrue(service.testConnection(request).passed());
        verify(security).requireManageStorage(42); verify(policy).validate("https://objects.example.test");
        var captured = ArgumentCaptor.forClass(ListObjectsV2Request.class); verify(client).listObjectsV2(captured.capture());
        assertEquals(1, captured.getValue().maxKeys()); assertEquals("documents/", captured.getValue().prefix());
        assertEquals("course-files", captured.getValue().bucket()); verify(client).close();
        verifyNoInteractions(configs); verifyNoMoreInteractions(client);
    }
    @Test void failedAuthorizationOccursBeforeDnsCredentialsOrConfigurationRead() {
        doThrow(new BizException("forbidden")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> service.testConnection(request(StorageTypeEnum.S3)));
        assertThrows(BizException.class, () -> service.applySavedCors(42, true));
        verifyNoInteractions(tenants, configs, policy, factory, client);
    }
    @Test void unsupportedProtocolsNeverFallBackToNormalInitializationOrSave() {
        assertThrows(BizException.class, () -> service.testConnection(request(StorageTypeEnum.FTP)));
        verifyNoInteractions(configs, policy, factory);
    }
    @Test void webDavProbeIsExplicitlyReadOnlyAndNeverInitializesOrSaves() {
        var probe = mock(WebDavReadOnlyProbe.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "webDavProbe", probe);
        var request = request(StorageTypeEnum.WEBDAV);
        assertTrue(service.testConnection(request).passed());
        verify(probe).assertReadable(request.getStorageSourceAllParam());
        verifyNoInteractions(configs, policy, factory, client);
    }
    @Test void privateEndpointPolicyFailurePreventsClientConstruction() {
        doThrow(new BizException("public only")).when(policy).validate(anyString());
        assertThrows(BizException.class, () -> service.testConnection(request(StorageTypeEnum.S3)));
        verifyNoInteractions(factory, client, configs);
    }
    @Test void malformedBucketPrefixAndMissingProtocolAreRejectedWithoutDns() {
        for (int caseId = 0; caseId < 3; caseId++) {
            var request = request(StorageTypeEnum.S3);
            if (caseId == 0) request.getStorageSourceAllParam().setBucketName("bucket/escape");
            if (caseId == 1) request.getStorageSourceAllParam().setBasePath("/../private");
            if (caseId == 2) request.getStorageSourceAllParam().setEndPointScheme(null);
            assertThrows(BizException.class, () -> service.testConnection(request));
        }
        verifyNoInteractions(policy, factory, client, configs);
    }
    @Test void providerFailureNeverEchoesCredentialsOrAutomaticallyRetries() throws Exception {
        availableClient(); when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(new RuntimeException("synthetic-secret signed-query"));
        var failure = assertThrows(BizException.class, () -> service.testConnection(request(StorageTypeEnum.S3)));
        assertFalse(failure.getMessage().contains("synthetic-secret")); assertFalse(failure.getMessage().contains("signed-query"));
        verify(client, times(1)).listObjectsV2(any(ListObjectsV2Request.class)); verify(client).close();
    }
    @Test void corsRequiresExplicitConfirmationBeforeReadingCredentials() {
        savedRules(); assertThrows(BizException.class, () -> service.applySavedCors(42, false));
        verifyNoInteractions(configs, policy, factory, client);
    }
    @Test void explicitCorsUsesFreshSavedConfigurationAndPerformsExactlyOneRemoteMutation() throws Exception {
        savedRules(); availableClient(); service.applySavedCors(42, true);
        verify(security).requireSystemAdmin(); verify(security, times(2)).requireManageStorage(42);
        verify(configs).selectFreshStorageConfigByStorageId(42);
        verify(factory).create(any(), eq("test-region"), eq("saved-synthetic-access"), eq("saved-synthetic-secret"));
        var captured = ArgumentCaptor.forClass(PutBucketCorsRequest.class); verify(client).putBucketCors(captured.capture());
        assertEquals("saved-bucket", captured.getValue().bucket()); assertEquals(1, captured.getValue().corsConfiguration().corsRules().size());
        verify(client).close(); verifyNoMoreInteractions(client);
    }
    @Test void invalidOrEmptyCorsRulesCannotClearRemotePolicies() {
        assertThrows(BizException.class, () -> StorageSourceSafetyService.validateCors(List.of()));
        var rule = new TlgxyCloudCORSRule(); rule.setAllowedMethods(List.of("TRACE")); rule.setAllowedOrigins(List.of("*"));
        assertThrows(BizException.class, () -> StorageSourceSafetyService.validateCors(List.of(rule)));
        rule.setAllowedMethods(List.of("PUT")); rule.setAllowedOrigins(List.of("https://user:secret@cloud.example.test"));
        assertThrows(BizException.class, () -> StorageSourceSafetyService.validateCors(List.of(rule)));
        rule.setAllowedOrigins(List.of("*")); rule.setAllowedHeaders(List.of("bad\nheader"));
        assertThrows(BizException.class, () -> StorageSourceSafetyService.validateCors(List.of(rule)));
        verifyNoInteractions(factory, client);
    }
}
