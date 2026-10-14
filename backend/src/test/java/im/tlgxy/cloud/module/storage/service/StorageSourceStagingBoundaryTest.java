package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.cache.metadata.StorageMetadataCache;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.admin.CopyStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.AopContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageSourceStagingBoundaryTest {
    @TempDir Path temporary;
    private final StorageSourceService service = new StorageSourceService();
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final StorageSourceMapper mapper = mock(StorageSourceMapper.class);
    private final StorageSourceConvert convert = mock(StorageSourceConvert.class);
    private final StorageSourceConfigService configs = mock(StorageSourceConfigService.class);
    private final StorageMetadataCache metadata = mock(StorageMetadataCache.class);
    private Path staging;

    @BeforeEach void configure() throws Exception {
        staging = Files.createDirectory(temporary.resolve("private-parts"));
        var properties = new ResumableUploadProperties(); properties.setEnabled(false); properties.setStagingRoot(staging.toString());
        ReflectionTestUtils.setField(service, "uploadStagingPolicy", new LocalUploadStagingPolicy(properties));
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        ReflectionTestUtils.setField(service, "storageSourceMapper", mapper);
        ReflectionTestUtils.setField(service, "storageSourceConvert", convert);
        ReflectionTestUtils.setField(service, "storageSourceConfigService", configs);
        ReflectionTestUtils.setField(service, "storageMetadataCache", metadata);
    }

    @Test void directCreateAndUpdateCannotBypassOptionalPreflight() {
        for (Integer id : new Integer[]{null, 42}) {
            for (Path root : List.of(temporary, staging, staging.resolve("public-child"))) {
                var request = new SaveStorageSourceRequest(); request.setId(id); request.setType(StorageTypeEnum.LOCAL); request.setEnable(false);
                var parameters = new StorageSourceAllParamDTO(); parameters.setFilePath(root.toString()); request.setStorageSourceAllParam(parameters);
                assertThrows(BizException.class, () -> service.saveStorageSource(request));
            }
        }
        verify(security, times(6)).requireSystemAdmin();
        verifyNoInteractions(mapper, convert, configs, metadata);
        assertFalse(Files.exists(staging.resolve("public-child")));
    }

    @Test void existingUnsafeSourceCannotBeCopiedAndFreshConfigIsRequired() {
        var source = new StorageSource(); source.setId(42); source.setType(StorageTypeEnum.LOCAL); source.setEnable(false);
        when(security.requireManageStorage(42)).thenReturn(source);
        var proxy = mock(StorageSourceService.class);
        try (var aop = mockStatic(AopContext.class)) {
            aop.when(AopContext::currentProxy).thenReturn(proxy);
            var request = new CopyStorageSourceRequest(); request.setFromId(42); request.setToKey("synthetic-copy"); request.setToName("synthetic-copy");
            var config = new StorageSourceConfig(); config.setName("filePath"); config.setValue(temporary.toString());
            when(configs.selectFreshStorageConfigByStorageId(42)).thenReturn(List.of(config));
            assertThrows(BizException.class, () -> service.copy(request));
            verify(configs).selectFreshStorageConfigByStorageId(42);
            verify(configs, never()).selectStorageConfigByStorageId(anyInt());
            verifyNoInteractions(metadata, mapper, convert);
            verify(proxy).existByStorageKey("synthetic-copy"); verifyNoMoreInteractions(proxy);
        }
    }

    @Test void ambiguousOrMissingStoredLocalRootCannotBeCopied() {
        var source = new StorageSource(); source.setId(42); source.setType(StorageTypeEnum.LOCAL);
        when(security.requireManageStorage(42)).thenReturn(source);
        var proxy = mock(StorageSourceService.class);
        try (var aop = mockStatic(AopContext.class)) {
            aop.when(AopContext::currentProxy).thenReturn(proxy);
            var request = new CopyStorageSourceRequest(); request.setFromId(42); request.setToKey("synthetic-copy");
            var config = new StorageSourceConfig(); config.setName("filePath"); config.setValue(temporary.resolve("cloud").toString());
            for (List<StorageSourceConfig> values : List.of(List.<StorageSourceConfig>of(), List.of(config, config))) {
                when(configs.selectFreshStorageConfigByStorageId(42)).thenReturn(values);
                assertThrows(BizException.class, () -> service.copy(request));
            }
            verifyNoInteractions(metadata, mapper, convert);
        }
    }
}
