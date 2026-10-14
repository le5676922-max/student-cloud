package im.tlgxy.cloud.core.cache.ratelimit;

import im.tlgxy.cloud.core.exception.core.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongSupplier;

/** Fixed windows beginning at the first hit, atomic both in Redis and in bounded process-local fallback. */
@Slf4j
public final class ApiRateLimiter {
    static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    private final boolean localFallback;
    private final int maxKeys;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();
    private volatile long retryRedisAt = Long.MIN_VALUE;

    public ApiRateLimiter(StringRedisTemplate redis, String prefix, boolean localFallback, int maxKeys) {
        this(redis, prefix, localFallback, maxKeys, () -> System.nanoTime() / 1_000_000);
    }

    ApiRateLimiter(StringRedisTemplate redis, String prefix, boolean localFallback, int maxKeys, LongSupplier clock) {
        if (maxKeys < 100 || maxKeys > 100_000) throw new IllegalArgumentException("Local rate limit capacity must be 100-100000");
        this.redis = redis;
        this.prefix = prefix;
        this.localFallback = localFallback;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public boolean allow(String scope, String subject, long maxCount, long windowMillis) {
        if (scope == null || subject == null || scope.length() > 1024 || subject.length() > 1024
                || maxCount < 1 || maxCount > 1_000_000 || windowMillis < 1 || windowMillis > 86_400_000) {
            throw new IllegalArgumentException("Invalid rate limit policy");
        }
        String key = prefix + digest(scope + '\0' + subject + '\0' + windowMillis + '\0' + maxCount);
        long now = clock.getAsLong();
        if (redis != null) {
            if (now >= retryRedisAt) {
                try {
                    Long count = redis.execute(SCRIPT, List.of(key), Long.toString(windowMillis));
                    if (count == null || count < 1) throw new IllegalStateException("Invalid rate limiter result");
                    return count <= maxCount;
                } catch (RuntimeException unavailable) {
                    if (now >= retryRedisAt) log.warn("Shared rate limiter unavailable ({}).", unavailable.getClass().getSimpleName());
                    retryRedisAt = now + 1_000;
                }
            }
            if (!localFallback) throw new BizException("请求保护服务暂不可用，请稍后重试");
        }
        return localAllow(key, maxCount, windowMillis, now);
    }

    private synchronized boolean localAllow(String key, long maxCount, long windowMillis, long now) {
        Window window = windows.get(key);
        if (window == null || now >= window.expiresAt) {
            if (window == null && windows.size() >= maxKeys) {
                windows.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
                // Never evict a live limiter key: doing so grants attackers a new window.
                if (windows.size() >= maxKeys) return false;
            }
            windows.put(key, new Window(1, now + windowMillis));
            return true;
        }
        if (window.count >= maxCount) return false;
        window.count++;
        return true;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }

    private static final class Window {
        private long count;
        private final long expiresAt;
        private Window(long count, long expiresAt) { this.count = count; this.expiresAt = expiresAt; }
    }
}
