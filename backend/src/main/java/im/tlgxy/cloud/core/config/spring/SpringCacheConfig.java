package im.tlgxy.cloud.core.config.spring;

import im.tlgxy.cloud.core.cache.metadata.MemoryStorageMetadataCache;
import im.tlgxy.cloud.core.cache.metadata.MetadataCacheProperties;
import im.tlgxy.cloud.core.cache.metadata.RedisStorageMetadataCache;
import im.tlgxy.cloud.core.cache.metadata.StorageMetadataCache;
import im.tlgxy.cloud.core.cache.RedisCacheClient;
import im.tlgxy.cloud.core.cache.overview.GlobalOverviewCache;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import im.tlgxy.cloud.core.cache.ratelimit.RateLimitProperties;
import im.tlgxy.cloud.core.cache.ratelimit.TrustedClientIpResolver;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

/**
 * Spring Cache 相关配置类。
 * <p>
 * 配置 Spring 的缓存管理器（CacheManager），主要用于支持 MyBatis-Plus 的二级缓存
 * 以及 {@code @Cacheable}、{@code @CacheEvict} 等注解驱动的缓存功能。
 * </p>
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 *   <li>
 *     <b>缓存实现：</b>使用 {@link ConcurrentMapCacheManager}（基于内存的 ConcurrentHashMap 实现），
 *     轻量且无需外部依赖。含密码/SMTP/AI 配置的现有 Spring Cache 始终保留在进程内，
 *     无凭据的存储展示元数据才会按独立开关使用 Redis。
 *   </li>
 *   <li>
 *     <b>事务感知：</b>通过 {@link TransactionAwareCacheManagerProxy} 包装，
 *     确保缓存的 put、evict、clear 操作延迟到当前事务成功提交后才执行，
 *     避免事务回滚时缓存已被更新的不一致问题。
 *   </li>
 *   <li>
 *     <b>缓存开关：</b>通过配置属性 {@code tongligong-student-cloud.dbCache.enable} 控制是否启用数据库缓存，
 *     当设置为 false 时使用 {@link NoOpCacheManager}（空操作的缓存管理器）禁用缓存。
 *   </li>
 * </ul>
 *
 * @author zhaojun
 */
@Configuration
// 启用 Spring 的注解驱动缓存功能
@EnableCaching
@EnableConfigurationProperties({MetadataCacheProperties.class, RateLimitProperties.class})
public class SpringCacheConfig {

    /**
     * 数据库缓存开关配置，从配置文件读取，默认为 true（开启）
     */
    @Value("${tongligong-student-cloud.dbCache.enable:true}")
    private Boolean dbCacheEnable;

    /**
     * 创建缓存管理器 Bean。
     * <p>
     * 使用 {@link TransactionAwareCacheManagerProxy} 包装 {@link ConcurrentMapCacheManager}，
     * 使缓存操作具备事务感知能力（缓存在事务提交后才生效）。
     * </p>
     * <p>
     * 条件：
     * </p>
     * <ul>
     *   <li>独立于 Sa-Token 会话提供者，不向 Redis 写入用户或系统配置实体。</li>
     *   <li>当 {@code tongligong-student-cloud.dbCache.enable} 为 false 时返回 NoOpCacheManager 禁用缓存。</li>
     * </ul>
     *
     * @return 配置好的 CacheManager 实例
     */
    @Bean
    public CacheManager cacheManager() {
        // 如果缓存开关关闭，返回空操作的缓存管理器（不缓存任何数据）
        // 否则返回事务感知的内存缓存管理器
        return BooleanUtils.isNotTrue(dbCacheEnable) ? new NoOpCacheManager() : new TransactionAwareCacheManagerProxy(new ConcurrentMapCacheManager());
    }

    @Bean(destroyMethod = "close")
    public StorageMetadataCache storageMetadataCache(MetadataCacheProperties properties, ObjectProvider<RedisCacheClient> clients) {
        Duration ttl = properties.getMetadataTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofSeconds(300)) > 0) {
            throw new IllegalArgumentException("Metadata cache TTL must be between 1 and 300 seconds");
        }
        if ("memory".equalsIgnoreCase(properties.getType())) return new MemoryStorageMetadataCache(ttl);
        if (!"redis".equalsIgnoreCase(properties.getType())) {
            throw new IllegalArgumentException("Metadata cache type must be memory or redis");
        }
        RedisCacheClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("Redis cache client is not configured");
        return new RedisStorageMetadataCache(client.template(), client.prefix("metadata"), ttl, () -> { });
    }

    @Bean(destroyMethod = "close")
    public RedisCacheClient redisCacheClient(MetadataCacheProperties properties, RateLimitProperties limits, Environment environment) {
        if (!"redis".equalsIgnoreCase(properties.getType()) && !"redis".equalsIgnoreCase(rateLimitType(properties, limits))) return null;
        String namespace = properties.getNamespace();
        if (namespace == null || !namespace.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Metadata cache namespace must use 1-64 letters, numbers, _ or -");
        }
        MetadataCacheProperties.Redis settings = properties.getRedis();
        if (settings.getDatabase() < 0 || settings.getPort() < 1 || settings.getPort() > 65535
                || settings.getTimeout() == null || settings.getTimeout().isNegative() || settings.getTimeout().isZero()) {
            throw new IllegalArgumentException("Invalid metadata Redis connection settings");
        }
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(settings.getHost(), settings.getPort());
        server.setDatabase(settings.getDatabase());
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) server.setUsername(settings.getUsername());
        if (settings.getPassword() != null && !settings.getPassword().isBlank()) server.setPassword(RedisPassword.of(settings.getPassword()));
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(settings.getTimeout()).shutdownTimeout(Duration.ofMillis(100))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(settings.getTimeout()).build()).build());
        if (settings.isSsl()) client.useSsl();
        // Intentionally private: this factory is not a bean and cannot change Sa-Token's connection/provider.
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client.build());
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String databaseIdentity = environment.getProperty("spring.datasource.url", "unconfigured-database");
        return new RedisCacheClient(redis, namespace + ":db-" + databaseFingerprint(databaseIdentity), factory::destroy);
    }

    @Bean
    public GlobalOverviewCache globalOverviewCache(MetadataCacheProperties properties, ObjectProvider<RedisCacheClient> clients) {
        Duration ttl = properties.getOverviewTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Overview cache TTL must be between 1 and 30 seconds");
        }
        RedisCacheClient client = "redis".equalsIgnoreCase(properties.getType()) ? clients.getIfAvailable() : null;
        return new GlobalOverviewCache(ttl, client == null ? null : client.template(),
                client == null ? "local:" : client.prefix("overview"));
    }

    @Bean
    public ApiRateLimiter apiRateLimiter(MetadataCacheProperties properties, RateLimitProperties limits,
                                       ObjectProvider<RedisCacheClient> clients) {
        String mode = limits.getFailureMode();
        if (!"closed".equalsIgnoreCase(mode) && !"local".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Rate limit failure mode must be closed or local");
        }
        String type = rateLimitType(properties, limits);
        RedisCacheClient client = "redis".equalsIgnoreCase(type) ? clients.getIfAvailable() : null;
        if ("redis".equalsIgnoreCase(type) && client == null) throw new IllegalStateException("Redis rate limiter is not configured");
        return new ApiRateLimiter(client == null ? null : client.template(), client == null ? "local:" : client.prefix("rate-limit"),
                "local".equalsIgnoreCase(mode), limits.getLocalMaxKeys());
    }

    @Bean
    public TrustedClientIpResolver trustedClientIpResolver(RateLimitProperties limits) {
        return new TrustedClientIpResolver(limits.getTrustedProxyCidrs());
    }

    private static String rateLimitType(MetadataCacheProperties properties, RateLimitProperties limits) {
        String type = "auto".equalsIgnoreCase(limits.getType()) ? properties.getType() : limits.getType();
        if (!"memory".equalsIgnoreCase(type) && !"redis".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Rate limit type must be auto, memory or redis");
        }
        return type;
    }

    private static String databaseFingerprint(String databaseIdentity) {
        try {
            // The JDBC URL/database is isolated by a digest, never copied into Redis keys/logs.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(databaseIdentity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}
