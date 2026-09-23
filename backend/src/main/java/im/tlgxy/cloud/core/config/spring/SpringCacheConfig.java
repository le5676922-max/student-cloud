package im.tlgxy.cloud.core.config.spring;

import im.tlgxy.cloud.core.config.security.SaTokenDaoRedisJackson;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 *     轻量且无需外部依赖。当配置了 Redis（即存在 {@link SaTokenDaoRedisJackson} Bean）时，
 *     此 CacheManager 不会被创建，改用 Redis 作为缓存存储。
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
     *   <li>仅当没有 {@link SaTokenDaoRedisJackson} Bean（即未配置 Redis）时才创建，
     *       否则优先使用 Redis 作为缓存。</li>
     *   <li>当 {@code tongligong-student-cloud.dbCache.enable} 为 false 时返回 NoOpCacheManager 禁用缓存。</li>
     * </ul>
     *
     * @return 配置好的 CacheManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(SaTokenDaoRedisJackson.class)
    public CacheManager cacheManager() {
        // 如果缓存开关关闭，返回空操作的缓存管理器（不缓存任何数据）
        // 否则返回事务感知的内存缓存管理器
        return BooleanUtils.isNotTrue(dbCacheEnable) ? new NoOpCacheManager() : new TransactionAwareCacheManagerProxy(new ConcurrentMapCacheManager());
    }

}
