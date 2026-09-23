package im.tlgxy.cloud.module.link.cache;

import cn.hutool.cache.impl.CacheObj;
import cn.hutool.cache.impl.TimedCache;
import im.tlgxy.cloud.module.link.model.dto.CacheInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直链/短链访问频率限制缓存
 * <p>
 * 基于 Hutool 的 {@link TimedCache} 实现，用于存储和统计每个客户端 IP 在指定时间窗口内的访问次数。
 * 缓存中的值使用 {@link AtomicInteger} 来保证并发环境下计数的原子性。
 * <p>
 * 主要功能：
 * <ul>
 *   <li>存储以客户端 IP 为 key、访问次数为 value 的缓存记录</li>
 *   <li>支持设置每条记录的超时时间，超时后自动清除</li>
 *   <li>提供缓存信息查询功能，用于管理后台查看当前限流状态</li>
 * </ul>
 * <p>
 * 默认超时时间为 60 秒（60000 毫秒），如果在 put 时不指定 timeout 参数则使用默认值。
 *
 * @author zhaojun
 */
@Service
public class LinkRateLimiterCache {

    /**
     * cache 在 put 时不指定 timeout, 则使用默认的 timeout. (单位: 毫秒)
     */
    public static final Integer DEFAULT_TIME_OUT = 60_000;

    /**
     * 带超时机制的缓存实例，key 为客户端 IP，value 为该 IP 的访问计数（原子整数）
     */
    private final TimedCache<String, AtomicInteger> timedCache = new TimedCache<>(DEFAULT_TIME_OUT);

    /**
     * 检查缓存中是否包含指定的 key
     *
     * @param key 缓存键，通常为客户端 IP 地址
     * @return true 表示缓存中存在该 key，false 表示不存在
     */
    public boolean containsKey(String key) {
        return timedCache.containsKey(key);
    }

    /**
     * 从缓存中获取指定 key 对应的值
     *
     * @param key                缓存键，通常为客户端 IP 地址
     * @param isUpdateLastAccess 是否更新最后访问时间（用于控制超时计时）
     * @return 对应的 {@link AtomicInteger} 访问计数对象，如果不存在则返回 null
     */
    public AtomicInteger get(String key, boolean isUpdateLastAccess) {
        return timedCache.get(key, isUpdateLastAccess);
    }

    /**
     * 向缓存中存入数据
     *
     * @param key    缓存键，通常为客户端 IP 地址
     * @param object 访问计数对象（{@link AtomicInteger}）
     * @param timeout 超时时间（毫秒），超过该时间后缓存记录自动失效
     */
    public void put(String key, AtomicInteger object, long timeout) {
        timedCache.put(key, object, timeout);
    }

    /**
     * 获取缓存中的所有记录信息
     * <p>
     * 遍历缓存中的所有条目，将每个条目的 key、value、TTL 和过期时间等信息
     * 封装为 {@link CacheInfo} 列表返回，用于管理后台展示当前限流状态。
     *
     * @return 缓存信息列表，包含每个缓存条目的详细信息
     */
    public List<CacheInfo<String, AtomicInteger>> getCacheInfo() {
        List<CacheInfo<String, AtomicInteger>> cacheInfoList = new ArrayList<>();

        // 遍历定时缓存中的所有条目
        Iterator<CacheObj<String, AtomicInteger>> cacheObjIterator = timedCache.cacheObjIterator();
        while (cacheObjIterator.hasNext()) {
            CacheObj<String, AtomicInteger> next = cacheObjIterator.next();
            // 将缓存对象转换为 CacheInfo DTO
            CacheInfo<String, AtomicInteger> cacheInfo = new CacheInfo<>();
            cacheInfo.setKey(next.getKey());
            cacheInfo.setValue(next.getValue());
            cacheInfo.setTtl(next.getTtl());
            cacheInfo.setExpiredTime(next.getExpiredTime());
            cacheInfoList.add(cacheInfo);
        }

        return cacheInfoList;
    }

}