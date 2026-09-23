package im.tlgxy.cloud.module.link.model.dto;

import lombok.Data;

import java.util.Date;

/**
 * 缓存信息 DTO
 * <p>
 * 通用的缓存信息数据传输对象，用于封装缓存条目的详细信息。
 * 支持泛型，可以适配不同类型的缓存 key 和 value。
 * <p>
 * 主要用途：在管理后台展示 {@link im.tlgxy.cloud.module.link.cache.LinkRateLimiterCache}
 * 中的限流缓存状态，包括：
 * <ul>
 *   <li>缓存键（如客户端 IP 地址）</li>
 *   <li>缓存值（如访问计数）</li>
 *   <li>过期时间（缓存条目何时失效）</li>
 *   <li>TTL（Time To Live，剩余存活时间，单位：毫秒）</li>
 * </ul>
 *
 * @param   <K> 缓存键的类型
 * @param   <V> 缓存值的类型
 *
 * @author zhaojun
 */
@Data
public class CacheInfo<K, V> {

    /**
     * 缓存键，如客户端 IP 地址
     */
    private K key;

    /**
     * 缓存值，如访问计数的 {@link java.util.concurrent.atomic.AtomicInteger}
     */
    private V value;

    /**
     * 缓存条目的过期时间
     */
    private Date expiredTime;

    /**
     * TTL（Time To Live），剩余存活时间，单位：毫秒
     */
    private Long ttl;

}