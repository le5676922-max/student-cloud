package im.tlgxy.cloud.core.cache.ratelimit;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, real Redis tests. Only 127.0.0.1:16379 / DB 15, no application credentials or default Redis.
 * Each test owns a random namespace and deletes only its exact synthetic keys; never FLUSH*/
@EnabledIfSystemProperty(named = "student.cloud.redis.integration", matches = "true")
class RedisRateLimiterIntegrationTest {
    private LettuceConnectionFactory firstConnection, secondConnection;
    private StringRedisTemplate firstRedis, secondRedis;
    private ApiRateLimiter first, second;
    private String prefix;
    private boolean firstClosed;
    private final Set<String> ownedKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void connectOnlyToDedicatedLoopbackRedis() {
        int port = Integer.parseInt(System.getProperty("student.cloud.redis.port", "16379"));
        if (port != 16379) throw new IllegalArgumentException("Redis integration tests require the dedicated loopback port 16379; default/business Redis is forbidden");
        firstConnection = privateConnection(); secondConnection = privateConnection();
        firstRedis = new StringRedisTemplate(firstConnection); secondRedis = new StringRedisTemplate(secondConnection);
        prefix = "student-cloud:ratelimit:integration-" + UUID.randomUUID() + ":";
        first = new ApiRateLimiter(firstRedis, prefix, false, 1000);
        second = new ApiRateLimiter(secondRedis, prefix, false, 1000);
        try (var a = firstConnection.getConnection(); var b = secondConnection.getConnection()) {
            assertEquals("PONG", a.ping()); assertEquals("PONG", b.ping());
        }
    }

    @AfterEach
    void deleteOnlyExactOwnedRandomKeysAndClosePrivateClients() {
        try {
            // The second private client remains open even in the intentional first-client outage test.
            if (secondRedis != null && !ownedKeys.isEmpty()) secondRedis.delete(ownedKeys);
        } finally {
            if (firstConnection != null && !firstClosed) firstConnection.destroy();
            if (secondConnection != null) secondConnection.destroy();
        }
    }

    @Test
    void twoIndependentInstancesUseTheSameFixedWindowWithoutRawSubjectInKeys() {
        String key = ownKey("mail-delivery", "synthetic@example.test", 3, 30_000);
        assertTrue(first.allow("mail-delivery", "synthetic@example.test", 3, 30_000));
        assertTrue(second.allow("mail-delivery", "synthetic@example.test", 3, 30_000));
        assertTrue(first.allow("mail-delivery", "synthetic@example.test", 3, 30_000));
        assertFalse(second.allow("mail-delivery", "synthetic@example.test", 3, 30_000));
        assertEquals("4", secondRedis.opsForValue().get(key));
        assertFalse(key.contains("synthetic@example.test"));
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(first, "windows")).isEmpty());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(second, "windows")).isEmpty());
    }

    @Test
    void concurrentRequestsAcrossInstancesNeverExceedSharedAllowance() throws Exception {
        String key = ownKey("concurrent", "synthetic-actor", 11, 30_000);
        var start = new CountDownLatch(1); var allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int index = 0; index < 64; index++) {
                ApiRateLimiter limiter = index % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    if (limiter.allow("concurrent", "synthetic-actor", 11, 30_000)) allowed.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally { start.countDown(); }
        assertEquals(11, allowed.get());
        assertEquals("64", firstRedis.opsForValue().get(key));
        assertFalse(second.allow("concurrent", "synthetic-actor", 11, 30_000));
    }

    @Test
    void actualRedisTtlExpiresAndReopensTheWindowForAnotherInstance() throws Exception {
        String key = ownKey("ttl", "synthetic-actor", 1, 1000);
        assertTrue(first.allow("ttl", "synthetic-actor", 1, 1000));
        assertFalse(second.allow("ttl", "synthetic-actor", 1, 1000));
        Long remaining = firstRedis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(remaining); assertTrue(remaining > 0 && remaining <= 1000);
        Thread.sleep(1100); // Real server expiration, not a fake clock or forced EXPIRE/DEL reset.
        assertFalse(Boolean.TRUE.equals(secondRedis.hasKey(key)));
        assertTrue(second.allow("ttl", "synthetic-actor", 1, 1000));
        assertEquals("1", firstRedis.opsForValue().get(key));
    }

    @Test
    void closedTransportFailsClosedWithoutGrantingAProcessLocalWindow() {
        ownKey("outage", "synthetic-actor", 1, 30_000);
        assertTrue(first.allow("outage", "synthetic-actor", 1, 30_000));
        // Disconnect only this test's own client, never stop or reconfigure the Redis server.
        firstConnection.destroy(); firstClosed = true;
        assertThrows(BizException.class, () -> first.allow("outage", "synthetic-actor", 1, 30_000));
        assertThrows(BizException.class, () -> first.allow("outage", "another-synthetic-actor", 1, 30_000));
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(first, "windows")).isEmpty());
        assertFalse(second.allow("outage", "synthetic-actor", 1, 30_000), "The untouched server retains the shared exhausted window");
    }

    private String ownKey(String scope, String subject, long maxCount, long windowMillis) {
        try {
            String policy = scope + '\0' + subject + '\0' + windowMillis + '\0' + maxCount;
            String key = prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(policy.getBytes(StandardCharsets.UTF_8)));
            ownedKeys.add(key); return key;
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static LettuceConnectionFactory privateConnection() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        configuration.setDatabase(15);
        var connection = new LettuceConnectionFactory(configuration, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(100)).build());
        connection.afterPropertiesSet(); return connection;
    }
}
