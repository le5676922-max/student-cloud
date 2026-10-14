package im.tlgxy.cloud.core.cache;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TlgxyCloudCacheManagerTest {
    @Test
    void differentApplicationContextsCannotReuseAnotherDatabaseStorageCache() {
        var first = new TlgxyCloudCacheManager();
        var second = new TlgxyCloudCacheManager();
        StorageSource oldSource = new StorageSource(); oldSource.setId(1);
        StorageSource newSource = new StorageSource(); newSource.setId(9);
        assertEquals(1, first.findAllEnableOrderByOrderNum(6, id -> List.of(oldSource)).getFirst().getId());
        assertEquals(9, second.findAllEnableOrderByOrderNum(6, id -> List.of(newSource)).getFirst().getId());
        first.clearUserEnableStorageSourceCache();
        assertEquals(9, second.findAllEnableOrderByOrderNum(6, id -> { throw new AssertionError("Expected independent cached source"); }).getFirst().getId());
    }
}
