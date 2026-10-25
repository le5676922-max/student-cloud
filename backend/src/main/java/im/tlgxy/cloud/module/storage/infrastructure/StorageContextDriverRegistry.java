package im.tlgxy.cloud.module.storage.infrastructure;

import im.tlgxy.cloud.module.storage.application.port.StorageDriverRegistry;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import org.springframework.stereotype.Component;

/** Compatibility adapter: only this boundary knows how the existing driver registry is stored. */
@Component
public class StorageContextDriverRegistry implements StorageDriverRegistry {
    @Override
    public AbstractBaseFileService<?> getByStorageId(Integer storageId) {
        return StorageSourceContext.getByStorageId(storageId);
    }
}
