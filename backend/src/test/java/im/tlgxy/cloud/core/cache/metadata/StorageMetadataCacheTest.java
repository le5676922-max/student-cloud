package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageMetadataCacheTest {
    private static final String PREFIX = "student-cloud:metadata:v1:test:db-isolated:";
    private static final String GENERATION = UUID.randomUUID().toString();

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void memoryCacheCoalescesReadsCopiesEntitiesAndExpires() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger queries = new AtomicInteger();
        var cache = new MemoryStorageMetadataCache(Duration.ofSeconds(120), clock::get);
        StorageSource row = source("Original");
        var loader = (java.util.function.Supplier<List<StorageSource>>) () -> { queries.incrementAndGet(); return List.of(row); };
        List<StorageSource> first = cache.orderedList(loader);
        first.getFirst().setName("Not shared");
        row.setName("Database changed");
        assertEquals("Original", cache.orderedList(loader).getFirst().getName());
        assertEquals(1, queries.get());
        clock.set(Duration.ofSeconds(121).toNanos());
        assertEquals("Database changed", cache.orderedList(loader).getFirst().getName());
        assertEquals(2, queries.get());
    }

    @Test
    void transactionCommitInvalidatesAgainButRollbackDoesNotClaimCommit() {
        var cache = spy(new MemoryStorageMetadataCache(Duration.ofSeconds(120)));
        TransactionSynchronizationManager.initSynchronization();
        cache.prepareMutation();
        verify(cache).invalidate(false);
        verify(cache, never()).invalidate(true);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(cache).invalidate(true);
    }

    @Test
    void nestedMutationEntriesFenceOnlyTwicePerCommittedTransaction() {
        var cache = spy(new MemoryStorageMetadataCache(Duration.ofSeconds(120)));
        TransactionSynchronizationManager.initSynchronization();
        cache.prepareMutation();
        cache.prepareMutation();
        cache.prepareMutation();
        verify(cache).invalidate(false);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(cache).invalidate(true);
    }

    @Test
    void snapshotsUseAnExplicitJsonAllowlistAndRestoreEnums() throws Exception {
        String json = StorageMetadataJson.encode(List.of(source("Example")));
        assertFalse(json.contains("@class"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("storageSourceAllParam"));
        assertFalse(json.contains("accessToken"));
        assertFalse(json.contains("secretKey"));
        assertEquals(List.of(source("Example")), StorageMetadataJson.decode(json));
        assertThrows(Exception.class, () -> StorageMetadataJson.decode("{\"schema\":1,\"items\":[],\"password\":\"not-allowed\"}"));
        assertThrows(Exception.class, () -> StorageMetadataJson.decode("{\"schema\":999,\"items\":[]}"));
    }

    @Test
    void redisCacheHitDoesNotQueryDatabaseAndHasBoundedTtl() throws Exception {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(PREFIX + "storages:generation")).thenReturn(GENERATION);
        when(values.multiGet(anyCollection())).thenReturn(Arrays.asList(null, GENERATION), Arrays.asList(null, GENERATION),
                List.of(StorageMetadataJson.encode(List.of(source("Cached"))), GENERATION));
        @SuppressWarnings("unchecked")
        var loader = (java.util.function.Supplier<List<StorageSource>>) mock(java.util.function.Supplier.class);
        when(loader.get()).thenReturn(List.of(source("Cached")));
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        assertEquals("Cached", cache.orderedList(loader).getFirst().getName());
        assertEquals("Cached", cache.orderedList(loader).getFirst().getName());
        verify(loader).get();
        verify(values).set(eq(PREFIX + "storages:ordered:" + GENERATION), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void redisReadFailureFallsBackAndDoesNotResumeOldEntriesAutomatically() {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("test only"));
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        AtomicInteger queries = new AtomicInteger();
        var loader = (java.util.function.Supplier<List<StorageSource>>) () -> { queries.incrementAndGet(); return List.of(source("Fresh")); };
        assertEquals("Fresh", cache.orderedList(loader).getFirst().getName());
        assertEquals("Fresh", cache.orderedList(loader).getFirst().getName());
        assertEquals(2, queries.get());
        verify(values, times(1)).get(anyString());
    }

    @Test
    void redisInvalidationFailureFailsClosedAndDistinguishesAlreadyCommittedData() {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("test only")).when(values).set(anyString(), anyString());
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        assertTrue(assertThrows(MetadataCacheUnavailableException.class, cache::prepareMutation).getMessage().contains("本次修改未执行"));
        assertTrue(assertThrows(MetadataCacheUnavailableException.class, () -> cache.invalidate(true)).getMessage().contains("数据已保存"));
        assertEquals("Fresh", cache.orderedList(() -> List.of(source("Fresh"))).getFirst().getName());
    }

    @Test
    void oldDatabaseReadCannotPopulateNewGeneration() {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        AtomicReference<String> generation = new AtomicReference<>(GENERATION);
        when(values.get(PREFIX + "storages:generation")).thenAnswer(call -> generation.get());
        when(values.multiGet(anyCollection())).thenAnswer(call -> Arrays.asList(null, generation.get()));
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        cache.orderedList(() -> { generation.set(UUID.randomUUID().toString()); return List.of(source("Old snapshot")); });
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void healthyRedisHitsDoNotSerializeAllReaders() throws Exception {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(PREFIX + "storages:generation")).thenReturn(GENERATION);
        String payload = StorageMetadataJson.encode(List.of(source("Cached")));
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        when(values.multiGet(anyCollection())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return List.of(payload, GENERATION);
        });
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> cache.orderedList(() -> { throw new AssertionError("DB hit"); }));
            var second = workers.submit(() -> cache.orderedList(() -> { throw new AssertionError("DB hit"); }));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "cache hits must enter Redis concurrently"); release.countDown();
            assertEquals("Cached", first.get(2, TimeUnit.SECONDS).getFirst().getName());
            assertEquals("Cached", second.get(2, TimeUnit.SECONDS).getFirst().getName());
        } finally { release.countDown(); }
        verify(values, times(2)).multiGet(List.of(PREFIX + "storages:ordered:" + GENERATION, PREFIX + "storages:generation"));
    }

    @Test
    void changedFenceNeverReturnsOldPayloadAndRedisDatabaseErrorsStillPropagate() throws Exception {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(PREFIX + "storages:generation")).thenReturn(GENERATION);
        when(values.multiGet(anyCollection())).thenReturn(List.of(StorageMetadataJson.encode(List.of(source("Stale"))), UUID.randomUUID().toString()));
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        assertEquals("Fresh", cache.orderedList(() -> List.of(source("Fresh"))).getFirst().getName());
        assertThrows(IllegalStateException.class, () -> cache.orderedList(() -> { throw new IllegalStateException("DB failure"); }));
    }

    @Test
    void mutationCanInvalidateWhileCacheMissWaitsForDatabaseConnection() throws Exception {
        var template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        AtomicReference<String> generation = new AtomicReference<>(GENERATION);
        when(values.get(PREFIX + "storages:generation")).thenAnswer(call -> generation.get());
        when(values.multiGet(anyCollection())).thenAnswer(call -> Arrays.asList(null, generation.get()));
        doAnswer(call -> { generation.set(call.getArgument(1)); return null; }).when(values).set(eq(PREFIX + "storages:generation"), anyString());
        var cache = new RedisStorageMetadataCache(template, PREFIX, Duration.ofSeconds(120), () -> {});
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var read = workers.submit(() -> cache.orderedList(() -> {
                started.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); } return List.of(source("Old snapshot"));
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var mutation = workers.submit(() -> cache.invalidate(true)); mutation.get(1, TimeUnit.SECONDS);
            release.countDown(); read.get(2, TimeUnit.SECONDS);
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        } finally { release.countDown(); }
    }

    @Test
    void databaseFailureIsNotSilentlyRetriedOrCachedAsEmpty() {
        var cache = new MemoryStorageMetadataCache(Duration.ofSeconds(120));
        AtomicInteger queries = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.orderedList(() -> { queries.incrementAndGet(); throw new IllegalStateException("DB failure"); }));
        assertEquals(1, queries.get());
        assertEquals("Recovery", cache.orderedList(() -> List.of(source("Recovery"))).getFirst().getName());
    }

    static StorageSource source(String name) {
        StorageSource source = new StorageSource();
        source.setId(42);
        source.setName(name);
        source.setKey("example-drive");
        source.setEnable(true);
        source.setType(StorageTypeEnum.LOCAL);
        source.setSearchMode(SearchModeEnum.SEARCH_ALL_MODE);
        source.setTenantId(9);
        source.setOwnerUserId(12);
        return source;
    }
}
