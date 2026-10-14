package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.request.admin.UpdateStorageSortRequest;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageMetadataMutationTest {
    private final StorageSourceService service = new StorageSourceService();
    private final StorageSourceMapper mapper = mock(StorageSourceMapper.class);
    private final StorageMetadataCache cache = mock(StorageMetadataCache.class);
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TlgxyCloudCacheManager existingCache = mock(TlgxyCloudCacheManager.class);

    StorageMetadataMutationTest() {
        ReflectionTestUtils.setField(service, "storageSourceMapper", mapper);
        ReflectionTestUtils.setField(service, "storageMetadataCache", cache);
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        ReflectionTestUtils.setField(service, "studentCloudCacheManager", existingCache);
    }

    @Test
    void changedStorageIsAuthorizedThenFencedBeforeWrite() {
        var row = StorageMetadataCacheTest.source("Updated");
        when(security.requireManageStorage(42)).thenReturn(StorageMetadataCacheTest.source("Original"));
        service.updateById(row);
        var order = inOrder(security, cache, mapper);
        order.verify(security).requireManageStorage(42);
        order.verify(cache).prepareMutation();
        order.verify(mapper).updateById(row);
        verify(existingCache).clearUserEnableStorageSourceCache();
    }

    @Test
    void unavailableGenerationWritePreventsDatabaseMutation() {
        when(security.requireManageStorage(42)).thenReturn(StorageMetadataCacheTest.source("Original"));
        doThrow(new MetadataCacheUnavailableException(false)).when(cache).prepareMutation();
        assertThrows(MetadataCacheUnavailableException.class, () -> service.updateById(StorageMetadataCacheTest.source("Update blocked")));
        verifyNoInteractions(mapper, existingCache);
    }

    @Test
    void sortingValidatesEveryTargetBeforeFencingAndUpdating() {
        var request = new UpdateStorageSortRequest();
        request.setId(42);
        request.setOrderNum(5);
        service.updateStorageSort(List.of(request));
        var order = inOrder(security, cache, mapper);
        order.verify(security).requireManageStorage(42);
        order.verify(cache).prepareMutation();
        order.verify(mapper).updateSetOrderNumById(0, 42);
    }

    @Test
    void insertedStorageFencesMetadataAndEveryMutationEntryHasTransactionBoundary() throws Exception {
        StorageSource row = StorageMetadataCacheTest.source("Inserted");
        row.setId(null);
        doAnswer(call -> { row.setId(42); return 1; }).when(mapper).insert(row);
        assertEquals(42, service.saveOrUpdate(row).getId());
        var order = inOrder(cache, mapper);
        order.verify(cache).prepareMutation();
        order.verify(mapper).insert(row);
        assertNotNull(StorageSourceService.class.getMethod("deleteById", Integer.class).getAnnotation(Transactional.class));
        assertNotNull(StorageSourceService.class.getMethod("updateById", StorageSource.class).getAnnotation(Transactional.class));
        assertNotNull(StorageSourceService.class.getMethod("saveOrUpdate", StorageSource.class).getAnnotation(Transactional.class));
        assertNotNull(StorageSourceService.class.getMethod("updateStorageSort", List.class).getAnnotation(Transactional.class));
    }
}
