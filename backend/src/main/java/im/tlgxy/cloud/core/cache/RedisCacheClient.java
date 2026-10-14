package im.tlgxy.cloud.core.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

/** Dedicated cache connection, deliberately not a RedisConnectionFactory bean for session auto-configuration. */
public final class RedisCacheClient implements AutoCloseable {
    private final StringRedisTemplate template;
    private final String identity;
    private final Runnable destroy;

    public RedisCacheClient(StringRedisTemplate template, String identity, Runnable destroy) {
        this.template = template;
        this.identity = identity;
        this.destroy = destroy;
    }

    public StringRedisTemplate template() { return template; }
    public String prefix(String purpose) { return "student-cloud:" + purpose + ":v1:" + identity + ":"; }
    @Override public void close() { destroy.run(); }
}
