package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Versioned Redis display cache. Auth state and storage connection parameters are deliberately excluded. */
@Slf4j
public final class RedisStorageMetadataCache implements StorageMetadataCache {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final Duration ttl;
    private final Runnable closeConnection;
    private volatile boolean readsPaused;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Object loadMonitor = new Object();

    public RedisStorageMetadataCache(StringRedisTemplate redis, String prefix, Duration ttl, Runnable closeConnection) {
        this.redis = redis;
        this.prefix = prefix;
        this.ttl = ttl;
        this.closeConnection = closeConnection;
    }

    @Override
    public List<StorageSource> orderedList(Supplier<List<StorageSource>> loader) {
        if (readsPaused) return loader.get();
        lifecycle.readLock().lock();
        try {
            List<StorageSource> hit = readSnapshot();
            if (hit != null) return hit;
        } finally {
            lifecycle.readLock().unlock();
        }
        // Only cache misses compete for the load monitor. Healthy hits run concurrently.
        synchronized (loadMonitor) {
            if (readsPaused) return loader.get();
            String generation = null;
            lifecycle.readLock().lock();
            try {
                List<StorageSource> hit = readSnapshot();
                if (hit != null) return hit;
                if (!readsPaused) {
                    try { generation = currentGeneration(); }
                    catch (Exception failure) { pauseReads(failure); }
                }
            } finally { lifecycle.readLock().unlock(); }
            // No lifecycle lock is held while waiting for a DB connection. Otherwise a transaction's
            // afterCommit invalidation could deadlock behind a reader waiting for its pooled connection.
            // Database errors propagate, rather than being cached as an empty result.
            List<StorageSource> result = loader.get();
            if (generation != null) {
                lifecycle.readLock().lock();
                try {
                    if (!readsPaused && generation.equals(redis.opsForValue().get(generationKey()))) {
                        redis.opsForValue().set(payloadKey(generation), StorageMetadataJson.encode(result), ttl);
                    }
                } catch (Exception failure) { pauseReads(failure); }
                finally { lifecycle.readLock().unlock(); }
            }
            return result;
        }
    }

    private List<StorageSource> readSnapshot() {
        if (readsPaused) return null;
        try {
            String generation = currentGeneration();
            // MGET atomically reads the payload and fence in one Redis round trip.
            List<String> snapshot = redis.opsForValue().multiGet(List.of(payloadKey(generation), generationKey()));
            if (!readsPaused && snapshot != null && snapshot.size() == 2 && snapshot.get(0) != null
                    && generation.equals(snapshot.get(1))) {
                List<StorageSource> decoded = StorageMetadataJson.decode(snapshot.get(0));
                return readsPaused ? null : decoded;
            }
        } catch (Exception failure) { pauseReads(failure); }
        return null;
    }

    private String currentGeneration() {
        String generation = redis.opsForValue().get(generationKey());
        if (generation == null) {
            redis.opsForValue().setIfAbsent(generationKey(), UUID.randomUUID().toString());
            generation = redis.opsForValue().get(generationKey());
        }
        if (generation == null || !generation.matches("[a-f0-9-]{36}")) {
            throw new IllegalStateException("Invalid metadata generation");
        }
        return generation;
    }

    @Override
    public void invalidate(boolean databaseCommitted) {
        lifecycle.writeLock().lock();
        try {
            // Never delete/reset an incrementing counter: an evicted Redis key must not reuse an old generation.
            // Old generation payloads expire through their bounded TTL; no KEYS/SCAN/FLUSHDB is needed.
            redis.opsForValue().set(generationKey(), UUID.randomUUID().toString());
            readsPaused = false;
        } catch (Exception failure) {
            pauseReads(failure);
            throw new MetadataCacheUnavailableException(databaseCommitted);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void pauseReads(Exception failure) {
        if (!readsPaused) {
            // Exception messages can contain connection credentials. Log the class only.
            log.warn("Redis storage metadata cache paused; reads use database ({}).", failure.getClass().getSimpleName());
        }
        // Do not resume reading possibly stale entries automatically when Redis reconnects.
        // A successful generation change on mutation is required before reads resume.
        readsPaused = true;
    }

    String generationKey() { return prefix + "storages:generation"; }
    String payloadKey(String generation) { return prefix + "storages:ordered:" + generation; }

    @Override
    public void close() { closeConnection.run(); }
}
