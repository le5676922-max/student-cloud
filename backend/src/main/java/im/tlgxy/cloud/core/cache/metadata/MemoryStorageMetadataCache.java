package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Short-lived, request-coalescing cache for the single-instance/default configuration. */
public final class MemoryStorageMetadataCache implements StorageMetadataCache {
    private final long ttlNanos;
    private final LongSupplier clock;
    private List<StorageMetadataJson.Metadata> snapshot;
    private long expiresAt;
    private long generation;

    public MemoryStorageMetadataCache(Duration ttl) {
        this(ttl, System::nanoTime);
    }

    MemoryStorageMetadataCache(Duration ttl, LongSupplier clock) {
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
    }

    @Override
    public synchronized List<StorageSource> orderedList(Supplier<List<StorageSource>> loader) {
        if (snapshot != null && clock.getAsLong() < expiresAt) {
            return StorageMetadataJson.restore(snapshot);
        }
        long loadedGeneration = generation;
        List<StorageSource> result = loader.get();
        if (generation == loadedGeneration) {
            snapshot = StorageMetadataJson.capture(result);
            expiresAt = clock.getAsLong() + ttlNanos;
        }
        return StorageMetadataJson.restore(StorageMetadataJson.capture(result));
    }

    @Override
    public synchronized void invalidate(boolean databaseCommitted) {
        generation++;
        snapshot = null;
    }
}
