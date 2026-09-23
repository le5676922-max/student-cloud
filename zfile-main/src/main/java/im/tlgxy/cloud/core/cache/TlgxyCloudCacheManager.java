package im.tlgxy.cloud.core.cache;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * TlgxyCloud 业务缓存管理器。
 * <p>
 * 用于处理无法使用 JSR-107 标准缓存注解（如 @Cacheable）的业务缓存场景。
 * 使用内存级 {@link ConcurrentHashMap} 实现，主要缓存用户可用的存储源列表，
 * 避免每次请求都从数据库查询。
 * </p>
 * <p>
 * 缓存的生命周期与应用进程一致，重启后自动清空。
 * </p>
 */
@Component
public class TlgxyCloudCacheManager {

    /**
     * 用户可用的存储源列表缓存。
     * key 为用户 ID，value 为该用户可访问的存储源列表。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private static final Map<Integer, List<StorageSource>> userEnableStorageSourceCache = new ConcurrentHashMap<>();

    /**
     * 根据用户 ID 获取可用的存储源列表（按排序号升序排列）。
     * <p>
     * 使用 computeIfAbsent 保证原子性：如果缓存中已存在该用户的存储源列表则直接返回，
     * 否则调用 mappingFunction 从数据库查询并放入缓存。
     * </p>
     *
     * @param userId          用户 ID
     * @param mappingFunction 当缓存中不存在时，用于从数据源获取存储源列表的函数
     * @return 该用户可用的存储源列表
     */
    public List<StorageSource> findAllEnableOrderByOrderNum(Integer userId, Function<Integer, List<StorageSource>> mappingFunction) {
        return userEnableStorageSourceCache.computeIfAbsent(userId, mappingFunction);
    }

    /**
     * 清空所有用户的存储源缓存。
     * <p>
     * 通常在存储源配置发生变更后调用，确保下次访问时重新加载最新数据。
     * </p>
     */
    public void clearUserEnableStorageSourceCache() {
        userEnableStorageSourceCache.clear();
    }

    /**
     * 清除指定用户的存储源缓存。
     * <p>
     * 当某个用户的存储源权限发生变化时调用，确保该用户下次访问时获取最新数据。
     * </p>
     *
     * @param userId 用户 ID
     */
    public void clearUserEnableStorageSourceCache(Integer userId) {
        userEnableStorageSourceCache.remove(userId);
    }

}
