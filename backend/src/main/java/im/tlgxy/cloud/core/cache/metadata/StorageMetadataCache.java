package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.function.Supplier;

/** Display metadata only: never use this cache to authorize a user or a storage operation. */
public interface StorageMetadataCache extends AutoCloseable {

    List<StorageSource> orderedList(Supplier<List<StorageSource>> loader);

    /** Fence before mutation, then again after commit so an old in-flight reader cannot refill it. */
    default void prepareMutation() {
        boolean transactional = TransactionSynchronizationManager.isSynchronizationActive();
        if (transactional && TransactionSynchronizationManager.hasResource(this)) return;
        invalidate(false);
        if (transactional) {
            // High-level save/copy methods call the lower-level saveOrUpdate in the same transaction.
            // Fence just once before that transaction and once after its successful commit.
            TransactionSynchronizationManager.bindResource(this, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(true);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(StorageMetadataCache.this);
                }
            });
        }
    }

    void invalidate(boolean databaseCommitted);

    @Override
    default void close() { }
}
