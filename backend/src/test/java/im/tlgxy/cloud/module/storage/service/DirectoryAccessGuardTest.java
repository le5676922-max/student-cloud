package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryAccessGuardTest {
    private final DirectoryAccessGuard guard = new DirectoryAccessGuard();
    private final UserService users = mock(UserService.class);
    private final StorageSourceMapper sources = mock(StorageSourceMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final UserStorageSourceService cached = mock(UserStorageSourceService.class);
    private final StorageSource source = new StorageSource();
    DirectoryAccessGuardTest() {
        ReflectionTestUtils.setField(guard, "users", users); ReflectionTestUtils.setField(guard, "sources", sources);
        ReflectionTestUtils.setField(guard, "grants", grants); ReflectionTestUtils.setField(guard, "cachedGrants", cached);
        source.setId(2); source.setTenantId(9); source.setEnable(true); when(sources.selectById(2)).thenReturn(source);
        when(users.requireLoginUser(1)).thenReturn(actor(UserRoleEnum.USER));
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/members/u10", Set.of("download")));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/members/u10", Set.of("download")));
    }
    @Test void cacheHitStillReadsGrantAndActiveAccountEveryTime() {
        assertTrue(guard.require(1, 2, "/members/u10").canReadFile()); guard.require(1, 2, "/members/u10");
        verify(users, times(2)).requireLoginUser(1); verify(grants, times(2)).getByUserIdAndStorageId(1, 2);
    }
    @Test void disabledOrMissingGrantAndSourceCannotReuseCache() {
        var row = grant("/members/u10", Set.of("download")); row.setEnable(false);
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(row); assertThrows(BizException.class, () -> guard.require(1, 2, "/members/u10"));
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(null); assertThrows(BizException.class, () -> guard.require(1, 2, "/members/u10"));
        when(sources.selectById(2)).thenReturn(null); assertThrows(BizException.class, () -> guard.require(1, 2, "/members/u10"));
    }
    @Test void staleRootOrPermissionsFailClosedBeforeMetadataIo() {
        assertThrows(BizException.class, () -> guard.require(1, 2, "/previous-user"));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/members/u10", Set.of("download", "ignorePassword")));
        assertThrows(BizException.class, () -> guard.require(1, 2, "/members/u10"));
    }
    @Test void noPreviewOrDownloadPermissionDoesNotAcquireUrls() {
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/members/u10", Set.of()));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/members/u10", Set.of()));
        assertFalse(guard.require(1, 2, "/members/u10").canReadFile());
    }
    @Test void revokedAccountStopsBeforeLoadingAnyGrant() {
        doThrow(new BizException("停用")).when(users).requireLoginUser(1);
        assertThrows(BizException.class, () -> guard.require(1, 2, "/members/u10")); verifyNoInteractions(grants);
    }
    @Test void administratorUsesRootAndOrdinaryAuthorityWithoutAnyStoredOrCachedGrant() {
        when(users.requireLoginUser(1)).thenReturn(actor(UserRoleEnum.ADMIN));
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(null);
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(null);
        var effective = guard.require(1, 2, "/");
        assertTrue(effective.canReadFile()); assertEquals("/", effective.root());
        assertTrue(effective.permissions().containsAll(Set.of("upload", "delete", "createShareLink")));
        assertFalse(effective.permissions().contains("ignorePassword"));
        assertFalse(effective.permissions().contains("ignoreHidden"));
    }
    @Test void administratorLegacyRestrictionsCannotPersistButDriverRootMismatchStillFailsClosed() {
        when(users.requireLoginUser(1)).thenReturn(actor(UserRoleEnum.ADMIN));
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/limited", Set.of()));
        var disabled = grant("/old", Set.of("ignorePassword")); disabled.setEnable(false);
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(disabled);
        assertTrue(guard.require(1, 2, "/").canReadFile());
        assertThrows(BizException.class, () -> guard.require(1, 2, "/old"));
        source.setEnable(false);
        assertThrows(BizException.class, () -> guard.require(1, 2, "/"));
    }
    @Test void subsequentRoleChecksDoNotRetainAdministratorEffectivePermissions() {
        when(users.requireLoginUser(1)).thenReturn(actor(UserRoleEnum.ADMIN), actor(UserRoleEnum.USER));
        assertEquals("/", guard.require(1, 2, "/").root());
        assertEquals("/members/u10", guard.require(1, 2, "/members/u10").root());
        verify(users, times(2)).requireLoginUser(1);
    }
    private static User actor(UserRoleEnum role) {
        User actor = new User(); actor.setId(role == UserRoleEnum.USER ? 10 : 1); actor.setTenantId(9); actor.setRole(role); actor.setEnable(true); return actor;
    }
    static UserStorageSource grant(String root, Set<String> permissions) {
        var row = new UserStorageSource(); row.setEnable(true); row.setRootPath(root); row.setPermissions(permissions); return row;
    }
}
