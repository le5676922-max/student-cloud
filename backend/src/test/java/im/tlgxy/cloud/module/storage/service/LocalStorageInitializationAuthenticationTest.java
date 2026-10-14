package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStorageInitializationAuthenticationTest {
    @TempDir Path directory;

    @Test
    void tokenlessConnectionCheckDoesNotAuthorizeInitializedFileAccess() {
        var permissions = mock(UserStorageSourceService.class);
        var storage = new LocalServiceImpl();
        ReflectionTestUtils.setField(storage, "userStorageSourceService", permissions);
        var parameters = new LocalParam();
        ReflectionTestUtils.setField(parameters, "filePath", directory.toString());
        storage.init("Synthetic registration drive", 123, parameters);

        try (var identities = mockStatic(TlgxyCloudAuthUtil.class)) {
            identities.when(TlgxyCloudAuthUtil::getCurrentUserId).thenThrow(new BizException("Login required"));
            assertDoesNotThrow(storage::testConnection);
            assertTrue(storage.isInitialized());
            identities.verifyNoInteractions();
            verify(permissions).getByUserIdAndStorageId(UserConstant.ADMIN_ID, 123);

            assertThrows(BizException.class, () -> storage.fileList("/"));
            identities.verify(TlgxyCloudAuthUtil::getCurrentUserId);
            verifyNoMoreInteractions(permissions);
        }
    }
}
