package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
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
    DirectoryAccessGuardTest() {
        ReflectionTestUtils.setField(guard, "users", users); ReflectionTestUtils.setField(guard, "sources", sources);
        ReflectionTestUtils.setField(guard, "grants", grants); ReflectionTestUtils.setField(guard, "cachedGrants", cached);
        StorageSource source = new StorageSource(); source.setEnable(true); when(sources.selectById(2)).thenReturn(source);
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/own", Set.of("download")));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/own", Set.of("download")));
    }
    @Test void cacheHitStillReadsGrantAndActiveAccountEveryTime() {
        assertTrue(guard.require(1, 2, "/own").canReadFile()); guard.require(1, 2, "/own");
        verify(users, times(2)).requireLoginUser(1); verify(grants, times(2)).getByUserIdAndStorageId(1, 2);
    }
    @Test void disabledOrMissingGrantAndSourceCannotReuseCache() {
        var row = grant("/own", Set.of("download")); row.setEnable(false);
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(row); assertThrows(BizException.class, () -> guard.require(1, 2, "/own"));
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(null); assertThrows(BizException.class, () -> guard.require(1, 2, "/own"));
        when(sources.selectById(2)).thenReturn(null); assertThrows(BizException.class, () -> guard.require(1, 2, "/own"));
    }
    @Test void staleRootOrPermissionsFailClosedBeforeMetadataIo() {
        assertThrows(BizException.class, () -> guard.require(1, 2, "/previous-user"));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/own", Set.of("download", "ignorePassword")));
        assertThrows(BizException.class, () -> guard.require(1, 2, "/own"));
    }
    @Test void noPreviewOrDownloadPermissionDoesNotAcquireUrls() {
        when(grants.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/own", Set.of()));
        when(cached.getByUserIdAndStorageId(1, 2)).thenReturn(grant("/own", Set.of()));
        assertFalse(guard.require(1, 2, "/own").canReadFile());
    }
    @Test void revokedAccountStopsBeforeLoadingAnyGrant() {
        doThrow(new BizException("停用")).when(users).requireLoginUser(1);
        assertThrows(BizException.class, () -> guard.require(1, 2, "/own")); verifyNoInteractions(grants);
    }
    static UserStorageSource grant(String root, Set<String> permissions) {
        var row = new UserStorageSource(); row.setEnable(true); row.setRootPath(root); row.setPermissions(permissions); return row;
    }
}
