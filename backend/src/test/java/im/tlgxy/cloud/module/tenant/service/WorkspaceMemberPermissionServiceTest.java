package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceMemberPermissionServiceTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StorageSourceMapper sources = mock(StorageSourceMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final WorkspaceJoinRequestMapper joins = mock(WorkspaceJoinRequestMapper.class);
    private final TlgxyCloudCacheManager cache = mock(TlgxyCloudCacheManager.class);
    private final WorkspaceMemberPermissionService service = new WorkspaceMemberPermissionService(security, tenants, users, sources, grants, joins, cache);
    private User operator;
    private User target;

    @BeforeEach void fixture() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "workspace-grant-test"), WorkspaceJoinRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "workspace-grant-rows"), UserStorageSource.class);
        operator = user(10, UserRoleEnum.TENANT_ADMIN, 9);
        target = user(21, UserRoleEnum.USER, 9);
        when(security.currentUser()).thenAnswer(ignored -> operator);
        when(users.selectById(21)).thenAnswer(ignored -> target);
        Tenant tenant = new Tenant(); tenant.setId(9); tenant.setEnable(true);
        when(tenants.selectById(9)).thenReturn(tenant);
        when(joins.selectCount(any())).thenReturn(0L);
        StorageSource source = new StorageSource(); source.setId(31); source.setTenantId(9); source.setName("课程资料"); source.setEnable(true);
        when(sources.selectById(31)).thenReturn(source);
        when(grants.selectByUserId(10)).thenReturn(List.of(grant(10, 31, "/team", Set.of("preview", "download", "ignorePassword", "ignoreHidden"))));
        when(grants.selectByUserId(21)).thenReturn(List.of());
    }

    @Test void readShowsOnlyDelegableDirectoriesAndRegularPermissions() {
        var view = service.get(21);
        assertEquals(Set.of("preview", "download"), view.allowedDefaultPermissions());
        assertEquals(1, view.storages().size());
        assertEquals("/team/members/u21", view.storages().getFirst().allowedRootPath());
        assertEquals(MemberStorageQuotaService.DEFAULT_QUOTA, view.storages().getFirst().quotaBytes());
        assertEquals(Set.of("preview", "download"), view.storages().getFirst().allowedPermissions());
        assertTrue(view.permissionOptions().stream().noneMatch(item -> Set.of("ignorePassword", "ignoreHidden").contains(item.value())));
    }

    @Test void globalAdminRegularMemberSelfAndForeignTargetsCannotReadOrWriteGrants() {
        for (UserRoleEnum role : List.of(UserRoleEnum.ADMIN, UserRoleEnum.USER)) {
            operator.setRole(role);
            assertThrows(BizException.class, () -> service.get(21));
            assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of("download"))));
        }
        operator.setRole(UserRoleEnum.TENANT_ADMIN);
        assertThrows(BizException.class, () -> service.get(10));
        target.setTenantId(8);
        assertThrows(BizException.class, () -> service.get(21));
        target.setTenantId(9); target.setRole(UserRoleEnum.TENANT_ADMIN);
        assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of("download"))));
        verify(users, never()).updateById(any(User.class));
        verify(grants, never()).insert(any(UserStorageSource.class));
    }

    @Test void parentBoundaryTraversalSensitiveBypassesAndUnknownSourcesAreRejectedBeforeWrites() {
        for (String root : List.of("/team-other", "/", "/team/../secret", "/team/%2e%2e", "/team\\private"))
            assertThrows(BizException.class, () -> service.save(21, update(root, Set.of("download"))), root);
        for (String permission : List.of("delete", "ignorePassword", "ignoreHidden", "unknown"))
            assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of(permission))));
        assertThrows(BizException.class, () -> service.save(21, new WorkspaceMemberPermissionService.UpdateRequest(Set.of(), List.of(
                new WorkspaceMemberPermissionService.StorageUpdate(32, "/team", true, Set.of("download"))))));
        verify(users, never()).updateById(any(User.class));
        verify(grants, never()).delete(any());
        verifyNoInteractions(cache);
    }

    @Test void narrowedGrantWritesOnlySubmittedSourceAndInvalidatesMemberAccessCache() {
        service.save(21, update("/team//members/u21/course/", Set.of("download")));
        var inserted = org.mockito.ArgumentCaptor.forClass(UserStorageSource.class);
        verify(grants).insert(inserted.capture());
        assertEquals(21, inserted.getValue().getUserId());
        assertEquals(31, inserted.getValue().getStorageSourceId());
        assertEquals("/team/members/u21/course", inserted.getValue().getRootPath());
        assertEquals(Set.of("download"), inserted.getValue().getPermissions());
        verify(grants, times(1)).delete(any());
        verify(cache).clearUserEnableStorageSourceCache(21);
    }

    @Test void aRoleChangePendingMembershipOrDisabledSpaceIsCheckedAgainOnSave() {
        service.get(21);
        operator.setRole(UserRoleEnum.ADMIN);
        assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of("download"))));
        operator.setRole(UserRoleEnum.TENANT_ADMIN);
        when(joins.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of("download"))));
        when(joins.selectCount(any())).thenReturn(0L);
        when(tenants.selectById(9)).thenReturn(null);
        assertThrows(BizException.class, () -> service.save(21, update("/team", Set.of("download"))));
        verify(users, never()).updateById(any(User.class));
    }

    @Test void anOuterApprovalTransactionInvalidatesGrantsOnlyAfterItsCommit() {
        var cacheManager=mock(org.springframework.cache.CacheManager.class);
        var permissionCache=mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache(im.tlgxy.cloud.module.user.service.UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY)).thenReturn(permissionCache);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"springCaches",cacheManager);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(21,update("/team/members/u21",Set.of("download")));
            verifyNoInteractions(cache,permissionCache);
            for(var callback:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            verify(cache).clearUserEnableStorageSourceCache(21);verify(permissionCache).clear();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }

    private static WorkspaceMemberPermissionService.UpdateRequest update(String root, Set<String> permissions) {
        return new WorkspaceMemberPermissionService.UpdateRequest(Set.of("preview"), List.of(
                new WorkspaceMemberPermissionService.StorageUpdate(31, root, true, permissions)));
    }
    private static User user(int id, UserRoleEnum role, int tenantId) {
        User result = new User(); result.setId(id); result.setRole(role); result.setTenantId(tenantId); result.setEnable(true); result.setDefaultPermissions(Set.of("preview", "download")); return result;
    }
    private static UserStorageSource grant(int userId, int sourceId, String root, Set<String> permissions) {
        UserStorageSource result = new UserStorageSource(); result.setUserId(userId); result.setStorageSourceId(sourceId); result.setRootPath(root); result.setEnable(true); result.setPermissions(permissions); return result;
    }
}
