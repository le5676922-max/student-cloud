package im.tlgxy.cloud.core.util;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.CacheObj;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OnlyOffice 文件信息与 Key 缓存工具类
 * <p>
 * 为 OnlyOffice 在线预览功能提供文件标识与缓存的管理。核心作用：
 * <ul>
 *     <li>维护 {@link OnlyOfficeFile} 和随机生成的唯一 Key 之间的双向映射关系</li>
 *     <li>对外暴露 Key，隐藏文件真实路径，提升安全性</li>
 *     <li>通过 LRU 缓存限制最大条目数，防止内存溢出</li>
 *     <li>通过 {@link ReentrantLock} 保证并发操作时的线程安全</li>
 * </ul>
 * <p>
 * 缓存结构：
 * <ul>
 *     <li>{@code ONLY_OFFICE_FILE_KEY_MAP}：以文件信息为键、Key 为值的 LRU 缓存（用于根据文件获取已存在的 Key）</li>
 *     <li>{@code ONLY_OFFICE_KEY_FILE_MAP}：以 Key 为键、文件信息为值的 LRU 缓存（用于根据 Key 反向查找文件）</li>
 *     <li>{@code locks}：为每个文件提供独立的可重入锁，防止并发读写冲突</li>
 * </ul>
 *
 * @author zhaojun
 */
@Slf4j
public class OnlyOfficeKeyCacheUtils {

    /**
     * 存储 OnlyOffice 文件信息与 Key 的映射关系
     * <p>
     * Key 为 {@link OnlyOfficeFile}，Value 为随机生成的唯一标识字符串。
     * 最多存储 10000 个 Key，使用 LRU（最近最少使用）策略淘汰，防止内存溢出。
     */
    private static final Cache<OnlyOfficeFile, String> ONLY_OFFICE_FILE_KEY_MAP = CacheUtil.newLRUCache(10000);

    /**
     * 存储 OnlyOffice Key 与文件信息的反向映射关系
     * <p>
     * Key 为随机生成的唯一标识字符串，Value 为 {@link OnlyOfficeFile} 文件信息。
     * 最多存储 10000 个 Key，使用 LRU 策略淘汰，防止内存溢出。
     * 用于根据外部传入的 Key 快速查找对应的文件信息。
     */
    private static final Cache<String, OnlyOfficeFile> ONLY_OFFICE_KEY_FILE_MAP = CacheUtil.newLRUCache(10000);

    /**
     * 文件级别的锁缓存
     * <p>
     * 为每个 {@link OnlyOfficeFile} 对象维护一个独立的 {@link ReentrantLock}，
     * 防止在并发操作同一文件缓存时出现数据不一致的问题。
     * 最多缓存 300 个锁对象，使用 LRU 策略淘汰。
     */
    private static final Cache<OnlyOfficeFile, ReentrantLock> locks = CacheUtil.newLRUCache(300);

    /**
     * 获取该文件缓存的 Key，如果不存在则生成一个新的 Key 并缓存
     * <p>
     * 操作是线程安全的：先获取文件级别的锁，然后在锁保护下检查缓存。
     * 如果缓存中已存在该文件的 Key，则直接返回；否则随机生成一个新的 Key
     * 并同时添加到双向映射缓存中。
     * <p>
     * 使用 tryLock 机制避免死锁：如果在指定超时时间内未获取到锁，
     * 会记录警告日志并强制忽略锁继续操作（作为一种降级策略）。
     *
     * @param onlyOfficeFile OnlyOffice 文件信息，不能为 null
     * @param timeout        获取锁的超时时间，单位：毫秒
     * @return 该文件的唯一标识 Key（10 位随机字母字符串）
     * @throws IllegalStateException 如果线程在等待锁时被中断
     */
    public static String getKeyOrPutNew(OnlyOfficeFile onlyOfficeFile, long timeout) {
        // 获取该文件对应的可重入锁
        ReentrantLock lock = getLock(onlyOfficeFile);
        try {
            // 尝试在指定时间内获取锁
            boolean getLock = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
            if (BooleanUtils.isFalse(getLock)) {
                // 超时未获取到锁，记录警告并强制降级：忽略锁直接操作
                log.warn("{} 尝试获取锁超时, 强制忽略锁直接操作文件.", onlyOfficeFile);
            }
            try {
                // 如果缓存中已存在该文件的 Key，直接返回
                if (ONLY_OFFICE_FILE_KEY_MAP.containsKey(onlyOfficeFile)) {
                    return ONLY_OFFICE_FILE_KEY_MAP.get(onlyOfficeFile);
                } else {
                    // 生成一个新的 10 位随机字母 Key 并建立双向映射
                    String key = RandomStringUtils.randomAlphabetic(10);
                    ONLY_OFFICE_FILE_KEY_MAP.put(onlyOfficeFile, key);
                    ONLY_OFFICE_KEY_FILE_MAP.put(key, onlyOfficeFile);
                    return key;
                }
            } finally {
                // 确保锁最终被释放
                lock.unlock();
            }
        } catch (InterruptedException e) {
            // 恢复线程的中断状态
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread was interrupted", e);
        }

    }

    /**
     * 根据 Key 移除缓存中的映射关系
     * <p>
     * 当文件发生变化需要重新生成 OnlyOffice 预览链接时调用此方法。
     * 该方法会同时移除双向映射中的条目：
     * <ol>
     *     <li>从 {@code ONLY_OFFICE_KEY_FILE_MAP} 中根据 Key 查找文件信息</li>
     *     <li>从 {@code ONLY_OFFICE_FILE_KEY_MAP} 中根据文件信息移除映射</li>
     *     <li>从 {@code ONLY_OFFICE_KEY_FILE_MAP} 中移除 Key 映射</li>
     * </ol>
     *
     * @param key 文件的唯一标识 Key，不能为 null
     * @return 被移除的文件信息；如果缓存中不存在该 Key 则返回 null
     */
    public static OnlyOfficeFile removeByKey(String key) {
        OnlyOfficeFile onlyOfficeFile = ONLY_OFFICE_KEY_FILE_MAP.get(key);
        if (onlyOfficeFile == null) {
            return null;
        }
        // 从双向映射中同时移除
        ONLY_OFFICE_FILE_KEY_MAP.remove(onlyOfficeFile);
        ONLY_OFFICE_KEY_FILE_MAP.remove(key);
        return onlyOfficeFile;
    }

    /**
     * 根据文件信息移除缓存中的映射关系
     * <p>
     * 当文件发生变化需要重新生成 OnlyOffice 预览链接时调用此方法。
     * 该方法会同时移除双向映射中的条目。
     *
     * @param onlyOfficeFile OnlyOffice 文件信息，不能为 null
     * @return 被移除的文件信息；如果缓存中不存在该文件则返回 null
     */
    public static OnlyOfficeFile removeByFile(OnlyOfficeFile onlyOfficeFile) {
        String key = ONLY_OFFICE_FILE_KEY_MAP.get(onlyOfficeFile);
        if (key == null) {
            return null;
        }
        // 从双向映射中同时移除
        ONLY_OFFICE_FILE_KEY_MAP.remove(onlyOfficeFile);
        ONLY_OFFICE_KEY_FILE_MAP.remove(key);
        return onlyOfficeFile;
    }


    /**
     * 清理缓存中某个文件夹下所有文件的映射关系
     * <p>
     * 当某个文件夹发生整体变化时（如被删除、移动、批量修改），
     * 需要清理该文件夹下所有缓存的 OnlyOffice 预览链接。
     * <p>
     * 匹配逻辑：
     * <ol>
     *     <li>遍历 {@code ONLY_OFFICE_FILE_KEY_MAP} 中的所有条目</li>
     *     <li>如果缓存中的文件与传入文件属于同一个存储源（storageKey 相同）</li>
     *     <li>且缓存文件的路径以传入文件的路径为前缀（即位于同一目录下）</li>
     *     <li>则移除该缓存条目</li>
     * </ol>
     *
     * @param onlyOfficeFile 表示目标文件夹的 OnlyOffice 文件信息，不能为 null
     * @return 被移除的文件信息列表；如果没有匹配的缓存则返回空列表
     */
    public static List<OnlyOfficeFile> removeByFolder(OnlyOfficeFile onlyOfficeFile) {
        List<OnlyOfficeFile> caches = new ArrayList<>();
        // 获取缓存迭代器，遍历所有文件->Key 映射
        Iterator<CacheObj<OnlyOfficeFile, String>> cacheObjIterator = ONLY_OFFICE_FILE_KEY_MAP.cacheObjIterator();
        while (cacheObjIterator.hasNext()) {
            CacheObj<OnlyOfficeFile, String> cacheObj = cacheObjIterator.next();
            OnlyOfficeFile cacheOnlyOfficeFile = cacheObj.getKey();
            // 判断是否为同一存储源且路径在目标文件夹下
            if (cacheOnlyOfficeFile.getStorageKey().equals(onlyOfficeFile.getStorageKey())
                    && StringUtils.startWith(cacheOnlyOfficeFile.getPathAndName(), onlyOfficeFile.getPathAndName())) {
                // 从双向映射中同时移除
                ONLY_OFFICE_FILE_KEY_MAP.remove(cacheObj.getKey());
                ONLY_OFFICE_KEY_FILE_MAP.remove(cacheObj.getValue());
                caches.add(cacheOnlyOfficeFile);
            }
        }
        return caches;
    }

    /**
     * 获取文件对应的可重入锁对象
     * <p>
     * 如果该文件尚未分配锁，则创建一个新的 {@link ReentrantLock}。
     * 使用缓存确保同一个文件始终使用同一把锁，从而实现细粒度的并发控制。
     *
     * @param key OnlyOffice 文件信息，作为锁的标识
     * @return 该文件对应的可重入锁对象，保证不为 null
     */
    public static ReentrantLock getLock(OnlyOfficeFile key) {
        // 如果缓存中不存在，则通过 ReentrantLock::new 创建新的锁对象
        return locks.get(key, true, ReentrantLock::new);
    }

}