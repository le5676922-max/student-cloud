package im.tlgxy.cloud.module.storage.application.port;

import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;

/**
 * Resolves an initialized storage driver without exposing the static registry to callers.
 *
 * The current driver SPI is retained for compatibility; replacing that SPI is a separate migration.
 */
public interface StorageDriverRegistry {
    AbstractBaseFileService<?> getByStorageId(Integer storageId);
}
