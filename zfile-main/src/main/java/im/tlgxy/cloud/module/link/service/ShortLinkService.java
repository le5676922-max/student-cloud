package im.tlgxy.cloud.module.link.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.config.model.dto.LinkExpireDTO;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.link.event.DeleteExpireLinkEvent;
import im.tlgxy.cloud.module.link.mapper.ShortLinkMapper;
import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 短链服务
 * <p>
 * 负责短链的生成、查询、删除等核心业务逻辑。使用 Spring Cache 对短链查询进行缓存，
 * 提高高频访问场景下的查询性能。
 * <p>
 * 主要功能：
 * <ul>
 *   <li>生成短链：为存储源中的文件路径生成唯一的短链 key，支持设置过期时间</li>
 *   <li>查询短链：根据 key 精确查找短链，结果会被缓存</li>
 *   <li>删除短链：支持单个删除、批量删除、按存储源删除和删除过期短链</li>
 *   <li>缓存管理：使用 {@link Cacheable} 和 {@link CacheEvict} 注解管理缓存</li>
 *   <li>事件监听：监听存储源删除事件，自动清理关联的短链</li>
 * </ul>
 * <p>
 * 短链 key 生成策略：使用 6 位随机字符串，通过循环重试确保 key 的唯一性。
 * 永久短链（expireTime = -1）不会重复生成，如果已存在相同路径的永久短链则直接返回。
 * <p>
 * 缓存策略：所有查询方法使用 "shortLink" 缓存空间，
 * 写操作（新增、删除）都会清空整个缓存空间（allEntries = true），
 * 避免缓存与数据库数据不一致。
 *
 * @author zhaojun
 */
@Service
@Slf4j
@CacheConfig(cacheNames = "shortLink")
public class ShortLinkService {

    /**
     * 短链 Mapper，用于数据库操作
     */
    @Resource
    private ShortLinkMapper shortLinkMapper;

    /**
     * 系统配置服务，用于获取链接过期时间配置
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * Spring 事件发布器，用于发布删除过期短链等事件
     */
    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 根据短链接 key 查询短链接
     * <p>
     * 查询结果会被缓存，缓存 key 为短链的 key 值。
     * 缓存条件：key 不为 null 时才进行缓存。
     * 不缓存 null 结果（unless = "#result == null"），避免缓存穿透。
     *
     * @param   key
     *          短链接 key，6 位随机字符串
     *
     * @return  短链接信息，如果短链不存在或已过期则返回 null
     */
    @Cacheable(key = "#key", unless = "#result == null", condition = "#key != null")
    public ShortLink findByKey(String key) {
        return shortLinkMapper.findByKey(key);
    }

    /**
     * 根据存储源 ID、文件 URL 和过期时间查询短链接
     * <p>
     * 用于判断指定存储源和文件路径是否已存在短链。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   url
     *          文件路径
     *
     * @param   expireDate
     *          过期时间，可为 null
     *
     * @return  匹配的短链接信息，如果不存在则返回 null
     */
    public @Nullable ShortLink findByStorageIdAndUrl(Integer storageId, String url, @Nullable Date expireDate) {
        return shortLinkMapper.findByStorageIdAndUrl(storageId, url, expireDate);
    }


    /**
     * 为存储源指定路径生成短链接
     * <p>
     * 生成策略：
     * <ol>
     *   <li>校验过期时间是否在系统允许的范围内</li>
     *   <li>如果是永久链接（expireTime = -1），先检查是否已存在相同路径的永久短链，存在则直接返回</li>
     *   <li>随机生成 6 位短链 key</li>
     *   <li>检查 key 是否已存在（循环重试直到生成唯一的 key）</li>
     *   <li>创建短链记录并保存到数据库</li>
     * </ol>
     *
     * @param   storageId
     *          存储源 id
     *
     * @param   fullPath
     *          存储源中的文件完整路径
     *
     * @param   expireTime
     *          过期时间（单位：秒），-1 表示永久有效
     *
     * @return  生成后的短链接信息
     *
     * @throws BizException 如果过期时间不在系统允许的范围内
     */
    public ShortLink generatorShortLink(Integer storageId, String fullPath, Long expireTime) {
        // 校验过期时间是否合法
        boolean validate = checkExpireDateIsValidate(expireTime);
        if (!validate) {
            throw new BizException(ErrorCode.BIZ_EXPIRE_TIME_ILLEGAL);
        }

        // 永久链接不在重复生成
        if (Objects.equals(expireTime, ShortLink.PERMANENT_EXPIRE_TIME)) {
            ShortLink shortLink = findByStorageIdAndUrl(storageId, fullPath, ShortLink.PERMANENT_EXPIRE_DATE);
            if (shortLink != null) {
                return shortLink;
            }
        }

        ShortLink shortLink;
        String randomKey;
        int generateCount = 0;
        // 循环生成随机 key，直到找到不重复的 key
        do {
            // 获取短链
            randomKey = RandomUtil.randomString(6);
            // 通过 AOP 代理调用，确保 @Cacheable 注解生效
            shortLink = ((ShortLinkService) AopContext.currentProxy()).findByKey(randomKey);
            generateCount++;
        } while (shortLink != null);

        // 构建短链实体
        shortLink = new ShortLink();
        shortLink.setStorageId(storageId);
        shortLink.setUrl(fullPath);
        shortLink.setCreateDate(new Date());
        shortLink.setShortKey(randomKey);

        // 设置过期时间
        if (expireTime == -1) {
            // 永久链接，设置过期日期为 9999-12-31
            shortLink.setExpireDate(ShortLink.PERMANENT_EXPIRE_DATE);
        } else {
            // 临时链接，根据当前时间 + 有效期计算过期日期
            shortLink.setExpireDate(new Date(System.currentTimeMillis() + expireTime * 1000L));
        }


        if (log.isDebugEnabled()) {
            log.debug("生成直/短链: 存储源 ID: {}, 文件路径: {}, 短链 key {}, 随机生成直链冲突次数: {}",
                    shortLink.getStorageId(), shortLink.getUrl(), shortLink.getShortKey(), generateCount);
        }

        // 保存到数据库
        shortLinkMapper.insert(shortLink);
        return shortLink;
    }

    /**
     * 删除所有已过期的短链
     * <p>
     * 清理数据库中过期时间早于当前时间的所有短链记录。
     * 执行前会发布 {@link DeleteExpireLinkEvent} 事件通知其他组件。
     * 执行后会清空整个 shortLink 缓存空间。
     *
     * @return 被删除的短链数量
     */
    @CacheEvict(allEntries = true)
    public int deleteExpireLink() {
        // 发布删除过期短链事件
        applicationEventPublisher.publishEvent(new DeleteExpireLinkEvent());
        int deleteSize = shortLinkMapper.deleteExpireLink();
        log.info("删除过期直/短链 {} 条", deleteSize);
        return deleteSize;
    }

    /**
     * 根据 ID 删除单个短链
     * <p>
     * 删除后会清空整个 shortLink 缓存空间。
     *
     * @param   id
     *          要删除的短链 ID
     */
    @CacheEvict(allEntries = true)
    public void removeById(Integer id) {
        log.info("删除 id 为 {} 的直/短链", id);
        shortLinkMapper.deleteById(id);
    }

    /**
     * 批量删除短链
     * <p>
     * 在事务中执行批量删除操作，保证数据一致性。
     * 删除后会清空整个 shortLink 缓存空间。
     *
     * @param   ids
     *          要删除的短链 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(allEntries = true)
    public void removeBatchByIds(List<Integer> ids) {
        log.info("批量删除直/短链，id 集合为 {}", ids);
        shortLinkMapper.deleteBatchIds(ids);
    }

    /**
     * 根据存储源 ID 删除所有关联的短链
     * <p>
     * 当存储源被删除时，需要同步清理该存储源下的所有短链。
     * 删除后会清空整个 shortLink 缓存空间。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  被删除的短链数量
     */
    @CacheEvict(allEntries = true)
    public int deleteByStorageId(Integer storageId) {
        int deleteSize = shortLinkMapper.deleteByStorageId(storageId);
        log.info("删除存储源 ID 为 {} 的短链 {} 条", storageId, deleteSize);
        return deleteSize;
    }

    /**
     * 监听存储源删除事件，根据存储源 id 删除相关的短链
     * <p>
     * 当系统中的某个存储源被删除时，自动触发该方法清理该存储源下的所有短链。
     * 通过 AOP 代理调用确保 {@link CacheEvict} 注解生效。
     *
     * @param   storageSourceDeleteEvent
     *          存储源删除事件，包含被删除存储源的 ID、名称和类型信息
     */
    @EventListener
    public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
        Integer storageId = storageSourceDeleteEvent.getId();
        // 通过 AOP 代理调用，确保 @CacheEvict 生效
        int updateRows = ((ShortLinkService) AopContext.currentProxy()).deleteByStorageId(storageId);
        if (log.isDebugEnabled()) {
            log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源短链 {} 条",
                    storageId,
                    storageSourceDeleteEvent.getName(),
                    storageSourceDeleteEvent.getType().getDescription(),
                    updateRows);
        }
    }

    /**
     * 分页查询短链
     * <p>
     * 使用 MyBatis-Plus 的分页插件执行分页查询。
     *
     * @param   pages
     *          分页参数对象
     *
     * @param   queryWrapper
     *          查询条件包装器
     *
     * @return  分页查询结果
     */
    public Page<ShortLink> selectPage(Page<ShortLink> pages, Wrapper<ShortLink> queryWrapper) {
        return shortLinkMapper.selectPage(pages, queryWrapper);
    }

    /**
     * 检查过期时间是否在系统允许的范围内
     * <p>
     * 从系统配置中获取允许的链接过期时间列表，判断传入的过期时间是否在列表中。
     *
     * @param   expires
     *          要校验的过期时间（单位：秒）
     *
     * @return  true 表示过期时间合法，false 表示不合法
     */
    private boolean checkExpireDateIsValidate(Long expires) {
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();

        List<LinkExpireDTO> linkExpireTimeList = systemConfig.getLinkExpireTimes();

        for (LinkExpireDTO linkExpireDTO : linkExpireTimeList) {
            if (linkExpireDTO.getSeconds().equals(expires)) {
                return true;
            }
        }
        return false;
    }

}