package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageMetadataAuthorizationTest {
    @Test
    void everyCacheHitStillChecksFreshAdministratorRole() {
        var service = new StorageSourceService();
        var mapper = mock(StorageSourceMapper.class);
        var security = mock(TenantSecurityService.class);
        ReflectionTestUtils.setField(service, "storageSourceMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        ReflectionTestUtils.setField(service, "storageMetadataCache", new MemoryStorageMetadataCache(Duration.ofSeconds(120)));
        when(mapper.findAllOrderByOrderNum()).thenReturn(List.of(StorageMetadataCacheTest.source("Allowed")));
        assertEquals("Allowed", service.findManageableOrderByOrderNum().getFirst().getName());
        service.findManageableOrderByOrderNum();
        verify(security, times(2)).requireSystemAdmin();
        verify(mapper).findAllOrderByOrderNum();
        doThrow(new BizException("Role revoked")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, service::findManageableOrderByOrderNum);
        verify(mapper).findAllOrderByOrderNum();
    }
}
