package im.tlgxy.cloud.core.cache.metadata;

import im.tlgxy.cloud.core.config.spring.SpringCacheConfig;
import im.tlgxy.cloud.core.cache.overview.GlobalOverviewCache;
import im.tlgxy.cloud.core.cache.ratelimit.ApiRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataCacheConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SpringCacheConfig.class);

    @Test
    void defaultMemoryConfigurationNeedsNoRedisOrSessionChanges() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class).hasSingleBean(StorageMetadataCache.class);
            assertThat(context).hasSingleBean(ApiRateLimiter.class).hasSingleBean(GlobalOverviewCache.class);
            assertThat(context.getBean(StorageMetadataCache.class)).isInstanceOf(MemoryStorageMetadataCache.class);
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
        });
    }

    @Test
    void redisMetadataConfigurationDoesNotExposeSessionConnectionFactory() {
        runner.withPropertyValues("tongligong-student-cloud.cache.type=redis", "tongligong-student-cloud.cache.redis.port=16379")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheManager.class).hasSingleBean(StorageMetadataCache.class);
                    assertThat(context.getBean(StorageMetadataCache.class)).isInstanceOf(RedisStorageMetadataCache.class);
                    assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
                    assertThat(context.getEnvironment().containsProperty("spring.data.redis.host")).isFalse();
                });
    }

    @Test
    void invalidCacheConfigurationFailsEarlyWithoutLeakingSettings() {
        runner.withPropertyValues("tongligong-student-cloud.cache.type=unknown").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tongligong-student-cloud.cache.metadata-ttl=1h").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tongligong-student-cloud.cache.overview-ttl=1h").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tongligong-student-cloud.rate-limit.type=unknown").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tongligong-student-cloud.rate-limit.failure-mode=open").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("tongligong-student-cloud.rate-limit.local-max-keys=1").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sharedLimiterCanBeEnabledWithoutMovingDisplayOrSessionCaches() {
        runner.withPropertyValues("tongligong-student-cloud.cache.type=memory", "tongligong-student-cloud.rate-limit.type=redis",
                        "tongligong-student-cloud.cache.redis.port=16379")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiRateLimiter.class);
                    assertThat(context.getBean(StorageMetadataCache.class)).isInstanceOf(MemoryStorageMetadataCache.class);
                    assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
                });
    }
}
