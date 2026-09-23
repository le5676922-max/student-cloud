package im.tlgxy.cloud.module.filter.service;

import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.PatternMatcherUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.filter.mapper.FilterConfigMapper;
import im.tlgxy.cloud.module.filter.model.entity.FilterConfig;
import im.tlgxy.cloud.module.storage.event.StorageSourceCopyEvent;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存储源文件过滤配置服务类，负责过滤规则的增删改查和匹配校验。
 * <p>
 * 核心功能：
 * <ul>
 *   <li><b>规则查询</b>：按存储源和隐藏模式查询过滤规则，支持缓存</li>
 *   <li><b>批量保存</b>：先删后增的方式批量更新过滤规则，支持事务</li>
 *   <li><b>规则匹配</b>：使用 glob 模式匹配文件/文件夹名称，判断是否隐藏、禁止访问或禁止下载</li>
 *   <li><b>事件监听</b>：监听存储源删除事件自动清理关联规则，监听存储源复制事件自动复制规则</li>
 *   <li><b>权限校验</b>：支持用户级别的忽略隐藏文件权限</li>
 * </ul>
 *
 * @author zhaojun
 */
@Slf4j
@Service
@CacheConfig(cacheNames = "filterConfig")
public class FilterConfigService {

    /**
     * 过滤配置 Mapper，用于数据库操作
     */
    @Resource
    private FilterConfigMapper filterConfigMapper;

    /**
     * 用户存储源权限服务，用于校验用户是否有忽略隐藏文件的权限
     */
    @Resource
    private UserStorageSourceService userStorageSourceService;

    /**
     * 根据存储源 ID 获取该存储源的所有过滤规则配置。
     * 结果会被缓存，缓存 key 为 filter-base-{storageId}。
     *
     * @param storageId 存储源 ID
     * @return 该存储源的所有过滤规则配置列表
     */
    @Cacheable(key = "'filter-base-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageId(Integer storageId) {
        return filterConfigMapper.findByStorageId(storageId);
    }

    /**
     * 获取指定存储源中所有模式为"禁止访问"（inaccessible）的过滤规则。
     * 结果会被缓存，缓存 key 为 filter-inaccessible-{storageId}。
     *
     * @param storageId 存储源 ID
     * @return 禁止访问的过滤规则列表
     */
    @Cacheable(key = "'filter-inaccessible-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageIdAndInaccessible(Integer storageId) {
        return filterConfigMapper.findByStorageIdAndInaccessible(storageId);
    }

    /**
     * 获取指定存储源中所有模式为"禁止下载"（disable_download）的过滤规则。
     * 结果会被缓存，缓存 key 为 filter-disable-download-{storageId}。
     *
     * @param storageId 存储源 ID
     * @return 禁止下载的过滤规则列表
     */
    @Cacheable(key = "'filter-disable-download-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageIdAndDisableDownload(Integer storageId) {
        return filterConfigMapper.findByStorageIdAndDisableDownload(storageId);
    }

    /**
     * 批量保存指定存储源的过滤规则配置。
     * <p>
     * 采用"先删除后新增"的策略，在事务中运行以保证原子性：
     * <ol>
     *   <li>先删除该存储源下所有旧的过滤规则</li>
     *   <li>再逐一插入新的过滤规则</li>
     * </ol>
     * 删除操作会同时清除相关的缓存。
     *
     * @param storageId        存储源 ID
     * @param filterConfigList 新的过滤规则配置列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchSave(Integer storageId, List<FilterConfig> filterConfigList) {
        // 先删除旧规则（通过 AOP 代理触发缓存清除）
        ((FilterConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        log.info("更新存储源 ID 为 {} 的过滤规则 {} 条", storageId, filterConfigList.size());

        // 逐条插入新规则
        for (FilterConfig filterConfig : filterConfigList) {
            filterConfig.setId(null);
            filterConfig.setStorageId(storageId);
            filterConfigMapper.insert(filterConfig);

            if (log.isDebugEnabled()) {
                log.debug("新增过滤规则, 存储源 ID: {}, 表达式: {}, 描述: {}, 隐藏模式: {}",
                        filterConfig.getStorageId(), filterConfig.getExpression(),
                        filterConfig.getDescription(), filterConfig.getMode().getValue());
            }
        }
    }

    /**
     * 根据存储源 ID 删除该存储源下的所有过滤规则。
     * <p>
     * 该方法会同时清除三个相关的缓存：
     * <ul>
     *   <li>filter-base-{storageId} —— 所有规则缓存</li>
     *   <li>filter-inaccessible-{storageId} —— 禁止访问规则缓存</li>
     *   <li>filter-disable-download-{storageId} —— 禁止下载规则缓存</li>
     * </ul>
     *
     * @param storageId 存储源 ID
     * @return 删除的记录条数
     */
    @Caching(evict = {
            @CacheEvict(key = "'filter-base-' + #storageId"),
            @CacheEvict(key = "'filter-inaccessible-' + #storageId"),
            @CacheEvict(key = "'filter-disable-download-' + #storageId")
    })
    public int deleteByStorageId(Integer storageId) {
        int deleteSize = filterConfigMapper.deleteByStorageId(storageId);
        log.info("删除存储源 ID 为 {} 的过滤规则 {} 条", storageId, deleteSize);
        return deleteSize;
    }

    /**
     * 监听存储源删除事件，自动清理该存储源关联的所有过滤规则。
     * <p>
     * 当管理员删除某个存储源时，通过此事件监听器自动删除该存储源下的所有过滤配置，
     * 避免产生孤儿数据。
     *
     * @param storageSourceDeleteEvent 存储源删除事件
     */
    @EventListener
    public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
        Integer storageId = storageSourceDeleteEvent.getId();
        // 通过 AOP 代理调用以触发缓存清除
        int updateRows = ((FilterConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        if (log.isDebugEnabled()) {
            log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源过滤条件设置 {} 条",
                    storageId,
                    storageSourceDeleteEvent.getName(),
                    storageSourceDeleteEvent.getType().getDescription(),
                    updateRows);
        }
    }

    /**
     * 判断指定存储源中的文件路径是否为"禁止访问"模式。
     *
     * @param storageId 存储源 ID
     * @param path      文件或文件夹路径
     * @return 如果匹配禁止访问规则则返回 true，否则返回 false
     */
    public boolean checkFileIsInaccessible(Integer storageId, String path) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageIdAndInaccessible(storageId);
        return testPattern(storageId, filterConfigList, path);
    }

    /**
     * 判断指定存储源中的文件名是否匹配隐藏规则。
     * <p>
     * 只要文件名匹配任意一条过滤表达式，即表示该文件应该被隐藏。
     *
     * @param storageId 存储源 ID
     * @param fileName  文件名（或文件路径）
     * @return 如果匹配隐藏规则则返回 true，否则返回 false
     */
    public boolean checkFileIsHidden(Integer storageId, String fileName) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageId(storageId);
        return testPattern(storageId, filterConfigList, fileName);
    }

    /**
     * 判断指定存储源中的文件是否禁止下载。
     * <p>
     * 会同时检查文件名和所在父目录路径是否匹配禁止下载规则，
     * 只要其中一个匹配即返回 true。
     *
     * @param storageId 存储源 ID
     * @param fileName  文件名（包含路径）
     * @return 如果匹配禁止下载规则则返回 true，否则返回 false
     */
    public boolean checkFileIsDisableDownload(Integer storageId, String fileName) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageIdAndDisableDownload(storageId);
        // 获取文件所在的父目录路径
        String filePath = FileUtils.getParentPath(fileName);
        if (StringUtils.isEmpty(filePath)) {
            // 如果在根目录，只检查文件名
            return testPattern(storageId, filterConfigList, fileName);
        } else {
            // 同时检查文件名和父目录路径
            return testPattern(storageId, filterConfigList, fileName) || testPattern(storageId, filterConfigList, filePath);
        }
    }

    /**
     * 使用过滤规则列表对测试字符串进行 glob 模式匹配。
     * <p>
     * 匹配逻辑：
     * <ol>
     *   <li>如果规则列表为空，直接返回 false（无需过滤）</li>
     *   <li>如果当前用户拥有忽略隐藏文件的权限，直接返回 false</li>
     *   <li>遍历所有规则，使用 glob 模式匹配测试字符串</li>
     *   <li>只要匹配任意一条规则，立即返回 true</li>
     *   <li>所有规则都不匹配，返回 false</li>
     * </ol>
     *
     * @param storageId   存储源 ID（用于日志记录）
     * @param patternList 过滤规则列表
     * @param test        待测试的文件名或路径字符串
     * @return 如果测试字符串匹配任意一条规则则返回 true，否则返回 false
     */
    private boolean testPattern(Integer storageId, List<FilterConfig> patternList, String test) {
        // 规则列表为空，无需过滤
        if (CollectionUtils.isEmpty(patternList)) {
            if (log.isDebugEnabled()) {
                log.debug("过滤规则列表为空, 存储源 ID: {}, 测试字符串: {}", storageId, test);
            }
            return false;
        }

        // 检查当前用户是否有忽略隐藏文件的权限
        boolean isIgnoreHidden = userStorageSourceService.hasCurrentUserStorageOperatorPermission(storageId, FileOperatorTypeEnum.IGNORE_HIDDEN);
        if (isIgnoreHidden) {
            if (log.isDebugEnabled()) {
                log.debug("权限配置忽略过滤规则, 存储源 ID: {}, 测试字符串: {}", storageId, test);
            }
            return false;
        }

        // 遍历每条规则进行 glob 模式匹配
        for (FilterConfig filterConfig : patternList) {
            String expression = filterConfig.getExpression();

            // 表达式为空则跳过
            if (StringUtils.isEmpty(expression)) {
                if (log.isDebugEnabled()) {
                    log.debug("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 表达式为空，跳过该规则校验", storageId, expression, test);
                }
                continue;
            }

            try {
                // 使用兼容的 glob 模式进行匹配
                boolean match = PatternMatcherUtils.testCompatibilityGlobPattern(expression, test);

                if (log.isDebugEnabled()) {
                    log.debug("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 匹配结果: {}", storageId, expression, test, match);
                }

                if (match) {
                    return true;
                }
            } catch (Exception e) {
                log.error("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 匹配异常，跳过该规则.", storageId, expression, test, e);
            }
        }

        return false;
    }

    /**
     * 监听存储源复制事件，将源存储源的过滤规则复制到新存储源。
     * <p>
     * 当管理员复制某个存储源时，会同时复制该存储源下的所有过滤配置到新存储源。
     *
     * @param storageSourceCopyEvent 存储源复制事件
     */
    @EventListener
    public void onStorageSourceCopy(StorageSourceCopyEvent storageSourceCopyEvent) {
        Integer fromId = storageSourceCopyEvent.getFromId();
        Integer newId = storageSourceCopyEvent.getNewId();

        // 获取源存储源的所有过滤规则
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageId(fromId);

        // 逐条复制过滤规则到新存储源
        filterConfigList.forEach(filterConfig -> {
            FilterConfig newFilterConfig = new FilterConfig();
            BeanUtils.copyProperties(filterConfig, newFilterConfig);
            newFilterConfig.setId(null);
            newFilterConfig.setStorageId(newId);
            filterConfigMapper.insert(newFilterConfig);
        });

        log.info("复制存储源 ID 为 {} 的存储源过滤条件设置到存储源 ID 为 {} 成功, 共 {} 条", fromId, newId, filterConfigList.size());
    }

}
