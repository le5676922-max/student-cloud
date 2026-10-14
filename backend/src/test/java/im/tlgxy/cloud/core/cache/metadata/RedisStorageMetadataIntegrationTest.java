package im.tlgxy.cloud.core.cache.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Redis tests, no Spring/MySQL/application account/credentials involved.
 * Run: mvn -Dtest=RedisStorageMetadataIntegrationTest -Dstudent.cloud.redis.integration=true
 *          -Dstudent.cloud.redis.port=16379 test
 * Only loopback and DB 15 are allowed. Cleanup deletes exactly this test's random keys, never FLUSH*.
 */
@EnabledIfSystemProperty(named = "student.cloud.redis.integration", matches = "true")
class RedisStorageMetadataIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private RedisStorageMetadataCache cache;
    private String prefix;
    private final Set<String> ownedKeys = new HashSet<>();
    private boolean transportClosed;

    @BeforeEach
    void connectToDedicatedTestRedis() {
        int port = Integer.parseInt(System.getProperty("student.cloud.redis.port", "16379"));
        if (port != 16379) throw new IllegalArgumentException("Only the dedicated test Redis port 16379 is allowed");
        var settings = new RedisStandaloneConfiguration("127.0.0.1", port);
        settings.setDatabase(15);
        connection = new LettuceConnectionFactory(settings, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(100)).build());
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        prefix = "student-cloud:metadata:v1:integration-" + UUID.randomUUID() + ":db-synthetic:";
        cache = new RedisStorageMetadataCache(redis, prefix, Duration.ofSeconds(1), () -> {});
        ownedKeys.add(cache.generationKey());
        try (var conn = connection.getConnection()) { assertEquals("PONG", conn.ping()); }
    }

    @AfterEach
    void deleteOnlyOwnedRandomKeys() {
        if (redis != null && !transportClosed) {
            String generation = redis.opsForValue().get(cache.generationKey());
            if (generation != null) ownedKeys.add(cache.payloadKey(generation));
            redis.delete(ownedKeys);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (connection != null) connection.destroy();
    }

    @Test
    void hitsShareAcrossInstancesWithSafeJsonAndExpireByRealTtl() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        var loader = (java.util.function.Supplier<List<im.tlgxy.cloud.module.storage.model.entity.StorageSource>>) () -> {
            queries.incrementAndGet(); return List.of(StorageMetadataCacheTest.source("Shared display metadata"));
        };
        assertEquals("Shared display metadata", cache.orderedList(loader).getFirst().getName());
        String payloadKey = rememberCurrentPayload();
        String payload = redis.opsForValue().get(payloadKey);
        assertNotNull(payload);
        assertFalse(payload.contains("password"));
        assertFalse(payload.contains("@class"));
        Long remaining = redis.getExpire(payloadKey, TimeUnit.MILLISECONDS);
        assertNotNull(remaining);
        assertTrue(remaining > 0 && remaining <= 1000);
        var otherInstance = new RedisStorageMetadataCache(redis, prefix, Duration.ofSeconds(1), () -> {});
        assertEquals("Shared display metadata", otherInstance.orderedList(loader).getFirst().getName());
        assertEquals(1, queries.get());
        Thread.sleep(1100);
        otherInstance.orderedList(loader);
        assertEquals(2, queries.get());
    }

    @Test
    void changingGenerationAfterCommitRefreshesAllInstances() {
        cache.orderedList(() -> List.of(StorageMetadataCacheTest.source("Before")));
        rememberCurrentPayload();
        var reader = new RedisStorageMetadataCache(redis, prefix, Duration.ofSeconds(1), () -> {});
        assertEquals("Before", reader.orderedList(() -> fail("Must use current cache")).getFirst().getName());
        TransactionSynchronizationManager.initSynchronization();
        cache.prepareMutation();
        String beforeCommit = redis.opsForValue().get(cache.generationKey());
        cache.orderedList(() -> List.of(StorageMetadataCacheTest.source("Transaction still open")));
        rememberCurrentPayload();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertNotEquals(beforeCommit, redis.opsForValue().get(cache.generationKey()));
        assertEquals("After commit", reader.orderedList(() -> List.of(StorageMetadataCacheTest.source("After commit"))).getFirst().getName());
        rememberCurrentPayload();
    }

    @Test
    void inFlightOldQueryCannotRefillGenerationWrittenByAnotherInstance() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldRead = executor.submit(() -> cache.orderedList(() -> {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return List.of(StorageMetadataCacheTest.source("Old query"));
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = new RedisStorageMetadataCache(redis, prefix, Duration.ofSeconds(1), () -> {});
            String oldGeneration = redis.opsForValue().get(cache.generationKey());
            ownedKeys.add(cache.payloadKey(oldGeneration));
            second.invalidate(false);
            release.countDown();
            assertEquals("Old query", oldRead.get(5, TimeUnit.SECONDS).getFirst().getName());
            assertNull(redis.opsForValue().get(cache.payloadKey(oldGeneration)));
            assertEquals("New query", second.orderedList(() -> List.of(StorageMetadataCacheTest.source("New query"))).getFirst().getName());
            rememberCurrentPayload();
        } finally { release.countDown(); }
    }

    @Test
    void randomNamespacesCannotReadEachOthersMetadata() {
        cache.orderedList(() -> List.of(StorageMetadataCacheTest.source("Namespace one")));
        rememberCurrentPayload();
        String otherPrefix = "student-cloud:metadata:v1:integration-" + UUID.randomUUID() + ":db-synthetic:";
        var other = new RedisStorageMetadataCache(redis, otherPrefix, Duration.ofSeconds(1), () -> {});
        ownedKeys.add(other.generationKey());
        assertEquals("Namespace two", other.orderedList(() -> List.of(StorageMetadataCacheTest.source("Namespace two"))).getFirst().getName());
        ownedKeys.add(other.payloadKey(redis.opsForValue().get(other.generationKey())));
    }

    @Test
    void actualClosedClientFallsBackForReadsButBlocksMutations() {
        cache.orderedList(() -> List.of(StorageMetadataCacheTest.source("Cached before outage")));
        rememberCurrentPayload();
        // Only disconnect this test's private client; do not stop or modify the shared Redis server.
        redis.delete(ownedKeys);
        connection.destroy();
        transportClosed = true;
        assertEquals("Database after outage", cache.orderedList(() -> List.of(StorageMetadataCacheTest.source("Database after outage"))).getFirst().getName());
        assertTrue(assertThrows(MetadataCacheUnavailableException.class, cache::prepareMutation).getMessage().contains("本次修改未执行"));
    }

    private String rememberCurrentPayload() {
        String generation = redis.opsForValue().get(cache.generationKey());
        String key = cache.payloadKey(generation);
        ownedKeys.add(key);
        return key;
    }
}
