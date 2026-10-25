package im.tlgxy.cloud.module.storage.infrastructure;

import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageContextDriverRegistryTest {
    private final StorageContextDriverRegistry registry = new StorageContextDriverRegistry();

    @Test
    void initializedDriverIsReturnedUnchangedThroughTheCompatibilityAdapter() {
        AbstractBaseFileService<?> driver = mock(AbstractBaseFileService.class);
        try (var context = mockStatic(StorageSourceContext.class)) {
            context.when(() -> StorageSourceContext.getByStorageId(7)).thenReturn(driver);
            assertSame(driver, registry.getByStorageId(7));
            context.verify(() -> StorageSourceContext.getByStorageId(7));
            context.verifyNoMoreInteractions();
        }
    }

    @Test
    void missingDriverPreservesTheOriginalExceptionAndNeverCreatesAFallbackDriver() {
        var missing = new InvalidStorageSourceBizException(77);
        try (var context = mockStatic(StorageSourceContext.class)) {
            context.when(() -> StorageSourceContext.getByStorageId(77)).thenThrow(missing);
            assertSame(missing, assertThrows(InvalidStorageSourceBizException.class, () -> registry.getByStorageId(77)));
            context.verify(() -> StorageSourceContext.getByStorageId(77));
            context.verifyNoMoreInteractions();
        }
    }
}
