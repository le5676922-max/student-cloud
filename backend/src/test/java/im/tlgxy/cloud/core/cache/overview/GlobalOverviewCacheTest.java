package im.tlgxy.cloud.core.cache.overview;

import im.tlgxy.cloud.module.admin.service.GlobalOverviewService.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlobalOverviewCacheTest {
    @Test
    void localStatisticsAndPersonalDetailsAreBoundedByFixedShortTtl() {
        var clock = new AtomicLong(1000);
        var cache = new GlobalOverviewCache(Duration.ofSeconds(10), null, "test:", clock::get);
        var loaded = new AtomicInteger();
        var first = cache.statistics(() -> { loaded.incrementAndGet(); return statistics(clock.get()); });
        clock.set(10_999);
        assertSame(first, cache.statistics(() -> { throw new AssertionError("unexpected reload"); }));
        clock.set(11_000);
        assertNotSame(first, cache.statistics(() -> { loaded.incrementAndGet(); return statistics(clock.get()); }));
        assertEquals(2, loaded.get());
        var details = new GlobalOverviewCache.Details(List.of(), List.of());
        assertSame(details, cache.details(() -> details));
        assertSame(details, cache.details(() -> { throw new AssertionError("unexpected reload"); }));
        clock.set(21_000);
        assertNotSame(details, cache.details(() -> new GlobalOverviewCache.Details(List.of(), List.of())));
    }

    @Test
    void redisSerializationIsExplicitAggregateOnlyAndSharedHitKeepsOriginalAge() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var clock = new AtomicLong(1000);
        var first = new GlobalOverviewCache(Duration.ofSeconds(10), redis, "namespace-db:", clock::get);
        first.statistics(() -> statistics(clock.get()));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq("namespace-db:statistics"), payload.capture(), eq(Duration.ofSeconds(10)));
        String json = payload.getValue();
        for (String excluded : List.of("username", "accountId", "email", "password", "runtime", "preview", "recentLogins")) {
            assertFalse(json.contains(excluded), excluded);
        }
        first.details(() -> new GlobalOverviewCache.Details(List.of(), List.of(new RecentLogin(1, "private-account", 1000L, true))));
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(values.get("namespace-db:statistics")).thenReturn(json);
        clock.set(2000);
        var second = new GlobalOverviewCache(Duration.ofSeconds(10), redis, "namespace-db:", clock::get);
        assertEquals(1000, second.statistics(() -> { throw new AssertionError("unexpected reload"); }).generatedAt());
        clock.set(11_000);
        assertEquals(11_000, second.statistics(() -> statistics(clock.get())).generatedAt());
    }

    @Test
    void redisFailureFallsBackWithoutCachingLoaderErrorsOrExposingConnectionSecrets() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new IllegalStateException("secret-password"));
        var clock = new AtomicLong(1000);
        var cache = new GlobalOverviewCache(Duration.ofSeconds(10), redis, "test:", clock::get);
        assertThrows(IllegalStateException.class, () -> cache.statistics(() -> { throw new IllegalStateException("DB failure"); }));
        assertEquals(1000, cache.statistics(() -> statistics(clock.get())).generatedAt());
        assertEquals(1000, cache.statistics(() -> { throw new AssertionError("unexpected reload"); }).generatedAt());
        verify(values, times(1)).get(anyString());
    }

    @Test
    void concurrentMissesLoadAggregatesOnceAndUnknownSharedFieldsAreRejected() throws Exception {
        var cache = new GlobalOverviewCache(Duration.ofSeconds(10), null, "test:");
        var count = new AtomicInteger();
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(20);
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 20; index++) executor.submit(() -> {
                try {
                    start.await();
                    cache.statistics(() -> { count.incrementAndGet(); return statistics(System.currentTimeMillis()); });
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { finished.countDown(); }
            });
            start.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count.get());
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{\"schema\":1,\"password\":\"secret\",\"statistics\":null}");
        var shared = new GlobalOverviewCache(Duration.ofSeconds(10), redis, "test:");
        assertEquals(4, shared.statistics(() -> statistics(System.currentTimeMillis())).workspaces());
    }

    private GlobalOverviewCache.Statistics statistics(long generatedAt) {
        return new GlobalOverviewCache.Statistics(4, 12, 3, 8, 6, 2, generatedAt, ZoneId.systemDefault().getId(),
                new Statuses(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), new LoginSummary(2, 3),
                IntStream.range(0, 7).mapToObj(index -> new DailyLogin("2026-10-" + (index + 1), 1, 0)).toList());
    }
}
