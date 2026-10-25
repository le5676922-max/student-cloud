package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.cache.metadata.StorageMetadataCache;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageSourceTenantPolicyTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final StorageSourceService service = new StorageSourceService();
    private final StorageSourceMapper mapper = mock(StorageSourceMapper.class);
    private final StorageMetadataCache metadata = mock(StorageMetadataCache.class);
    StorageSourceTenantPolicyTest() {
        ReflectionTestUtils.setField(service,"tenantSecurityService",security);
        ReflectionTestUtils.setField(service,"storageSourceMapper",mapper);
        ReflectionTestUtils.setField(service,"storageMetadataCache",metadata);
    }
    private User actor(UserRoleEnum role) { var actor = new User(); actor.setId(9); actor.setTenantId(7); actor.setRole(role); when(security.requireStorageAdministrator()).thenReturn(actor); return actor; }
    @Test void cachedDisplayListIsFilteredAfterFreshActorCheck() {
        actor(UserRoleEnum.TENANT_ADMIN);
        var own = new StorageSource(); own.setTenantId(7); var foreign = new StorageSource(); foreign.setTenantId(8);
        when(metadata.orderedList(any())).thenReturn(List.of(own,foreign));
        assertEquals(List.of(own),service.findManageableOrderByOrderNum());
        actor(UserRoleEnum.ADMIN); assertEquals(2,service.findManageableOrderByOrderNum().size());
    }
    @Test void managerCannotCreateLocalChooseOtherTenantOrConvertExistingLocal() {
        actor(UserRoleEnum.TENANT_ADMIN);
        var request = new SaveStorageSourceRequest(); request.setType(StorageTypeEnum.LOCAL);
        assertThrows(BizException.class,()->service.saveStorageSource(request));
        request.setType(StorageTypeEnum.S3); request.setTenantId(8);
        assertThrows(BizException.class,()->service.saveStorageSource(request));
        request.setTenantId(7); request.setId(41);
        var local = new StorageSource(); local.setType(StorageTypeEnum.LOCAL); when(security.requireManageStorage(41)).thenReturn(local);
        assertThrows(BizException.class,()->service.saveStorageSource(request)); verifyNoInteractions(mapper,metadata);
    }
}
