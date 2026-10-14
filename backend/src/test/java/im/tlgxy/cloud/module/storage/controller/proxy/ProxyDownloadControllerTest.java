package im.tlgxy.cloud.module.storage.controller.proxy;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.ErrorPageBizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import im.tlgxy.cloud.core.util.ProxyDownloadUrlUtils;
import im.tlgxy.cloud.core.util.SpringMvcUtils;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProxyDownloadControllerTest {

    private static final String STORAGE_KEY = "synthetic-space-drive";
    private static final String FILE_PATH = "/synthetic.txt";
    private static final String SIGNATURE = "synthetic-valid-signature";

    private final StorageSourceMapper storageSources = mock(StorageSourceMapper.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final AbstractProxyTransferService<?> proxyService = mock(AbstractProxyTransferService.class);
    private final ProxyDownloadController controller = new ProxyDownloadController();
    private StorageSource storageSource;
    private Tenant tenant;
    private MockedStatic<SpringMvcUtils> mvc;
    private MockedStatic<StorageSourceContext> context;
    private MockedStatic<ProxyDownloadUrlUtils> signatures;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "storageSourceMapper", storageSources);
        ReflectionTestUtils.setField(controller, "tenantMapper", tenants);
        storageSource = new StorageSource();
        storageSource.setId(30);
        storageSource.setKey(STORAGE_KEY);
        storageSource.setTenantId(10);
        storageSource.setEnable(true);
        tenant = new Tenant();
        tenant.setId(10);
        tenant.setEnable(true);
        when(storageSources.findByStorageKey(STORAGE_KEY)).thenReturn(storageSource);
        when(tenants.selectById(10)).thenReturn(tenant);
        mvc = mockStatic(SpringMvcUtils.class);
        mvc.when(SpringMvcUtils::getExtractPathWithinPattern).thenReturn(FILE_PATH);
        context = mockStatic(StorageSourceContext.class);
        context.when(() -> StorageSourceContext.getByStorageKey(STORAGE_KEY)).thenReturn(proxyService);
        signatures = mockStatic(ProxyDownloadUrlUtils.class);
        signatures.when(() -> ProxyDownloadUrlUtils.validSignatureExpired(30, FILE_PATH, SIGNATURE)).thenReturn(true);
        when(proxyService.getStorageId()).thenReturn(30);
    }

    @AfterEach
    void closeStaticMocks() {
        signatures.close();
        context.close();
        mvc.close();
    }

    @Test
    void enabledSourceAndWorkspaceAllowAnonymousValidSignatureAndPreserveStreamResponse() throws Exception {
        ResponseEntity<Resource> response = ResponseEntity.ok(new ByteArrayResource(new byte[]{1, 2, 3}));
        when(proxyService.downloadToStream(FILE_PATH)).thenReturn(response);

        assertSame(response, controller.downAttachment(STORAGE_KEY, SIGNATURE, null));

        verify(storageSources).findByStorageKey(STORAGE_KEY);
        verify(tenants).selectById(10);
        signatures.verify(() -> ProxyDownloadUrlUtils.validSignatureExpired(30, FILE_PATH, SIGNATURE));
        verify(proxyService).downloadToStream(FILE_PATH);
    }

    @Test
    void disabledWorkspaceRejectsPreviouslyValidSignatureBeforeCachedContextOrStream() throws Exception {
        tenant.setEnable(false);
        assertUnavailableBeforeContext();
    }

    @Test
    void disabledSourceRejectsPreviouslyValidSignatureBeforeCachedContextOrStream() throws Exception {
        storageSource.setEnable(false);
        assertUnavailableBeforeContext();
        verifyNoInteractions(tenants);
    }

    @Test
    void missingWorkspaceRejectsPreviouslyValidSignatureBeforeCachedContextOrStream() throws Exception {
        when(tenants.selectById(10)).thenReturn(null);
        assertUnavailableBeforeContext();
    }

    @Test
    void missingSourceRejectsBeforeCachedContextOrStream() throws Exception {
        when(storageSources.findByStorageKey(STORAGE_KEY)).thenReturn(null);
        assertUnavailableBeforeContext();
        verifyNoInteractions(tenants);
    }

    @Test
    void sourceWithoutWorkspaceRejectsBeforeCachedContextOrStream() throws Exception {
        storageSource.setTenantId(null);
        assertUnavailableBeforeContext();
        verifyNoInteractions(tenants);
    }

    @Test
    void unspecifiedEnabledStateFailsClosed() throws Exception {
        tenant.setEnable(null);
        assertUnavailableBeforeContext();
        tenant.setEnable(true);
        storageSource.setEnable(null);
        assertUnavailableBeforeContext();
    }

    @Test
    void activeWorkspaceStillRejectsInvalidSignatureWithoutOpeningStream() throws Exception {
        signatures.when(() -> ProxyDownloadUrlUtils.validSignatureExpired(30, FILE_PATH, SIGNATURE)).thenReturn(false);

        ForbiddenAccessException exception = assertThrows(ForbiddenAccessException.class,
                () -> controller.downAttachment(STORAGE_KEY, SIGNATURE, null));

        assertEquals(ErrorCode.BIZ_INVALID_SIGNATURE.getCode(), exception.getCode());
        verify(proxyService, never()).downloadToStream(anyString());
    }

    private void assertUnavailableBeforeContext() throws Exception {
        NotFoundAccessException exception = assertThrows(NotFoundAccessException.class,
                () -> controller.downAttachment(STORAGE_KEY, SIGNATURE, null));
        assertEquals(ErrorCode.BIZ_STORAGE_NOT_FOUND.getCode(), exception.getCode());
        assertEquals(ErrorCode.BIZ_STORAGE_NOT_FOUND.getMessage(), exception.getMessage());
        context.verifyNoInteractions();
        signatures.verifyNoInteractions();
        verify(proxyService, never()).downloadToStream(anyString());
    }
}
