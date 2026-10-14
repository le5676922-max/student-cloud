package im.tlgxy.cloud.module.password.service;

import cn.hutool.core.util.ObjectUtil;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.util.CollectionUtils;
import im.tlgxy.cloud.core.util.PatternMatcherUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.password.mapper.PasswordConfigMapper;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.password.model.entity.PasswordConfig;
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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 存储源密码配置 Service
 * <p>
 * 封装密码配置的增删改查和密码校验业务逻辑，包括：
 * <ul>
 *   <li>按存储源 ID 查询密码规则（带缓存）</li>
 *   <li>批量保存密码规则（事务性操作，先删后增）</li>
 *   <li>密码校验：根据请求路径匹配密码规则，验证用户输入的密码</li>
 *   <li>监听存储源删除事件，自动清理关联的密码配置</li>
 *   <li>监听存储源复制事件，自动复制密码配置到新存储源</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Service
@Slf4j
@CacheConfig(cacheNames = "passwordConfig")
public class PasswordConfigService {

    /** 密码配置 Mapper */
    @Resource
    private PasswordConfigMapper passwordConfigMapper;

    /** 用户存储源权限服务，用于判断是否需要忽略密码校验 */
    @Resource
    private UserStorageSourceService userStorageSourceService;


    /**
     * 根据存储源 ID 查询密码规则列表（带缓存）
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  密码规则列表
     */
    @Cacheable(key = "#storageId",
            condition = "#storageId != null")
    public List<PasswordConfig> findByStorageId(Integer storageId) {
        return passwordConfigMapper.findByStorageId(storageId);
    }


    /**
     * 批量保存指定存储源 ID 的密码规则列表
     * <p>
     * 事务性操作：先删除该存储源的所有旧规则，再逐条插入新规则。
     * 通过 AopContext.currentProxy() 确保 @CacheEvict 等 AOP 增强生效。
     * </p>
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   passwordConfigList
     *          新的密码规则列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchSave(Integer storageId, List<PasswordConfig> passwordConfigList) {
        // 先删除旧规则（通过代理调用确保缓存注解生效）
        ((PasswordConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        log.info("更新存储源 ID 为 {} 的过滤规则 {} 条", storageId, passwordConfigList.size());

        // 逐条插入新规则
        passwordConfigList.forEach(passwordConfig -> {
            // 清空 ID，让数据库自动生成
            passwordConfig.setId(null);
            passwordConfig.setStorageId(storageId);
            passwordConfigMapper.insert(passwordConfig);

            if (log.isDebugEnabled()) {
                log.debug("新增过滤规则, 存储源 ID: {}, 表达式: {}, 描述: {}",
                        passwordConfig.getStorageId(), passwordConfig.getExpression(),
                        passwordConfig.getDescription());
            }
        });
    }


    /**
     * 根据存储源 id 删除所有密码规则（会清除缓存）
     *
     * @param   storageId
     *          存储源 ID
     * @return  删除的记录数
     */
    @CacheEvict(key = "#storageId")
    public int deleteByStorageId(Integer storageId) {
        int deleteSize = passwordConfigMapper.deleteByStorageId(storageId);
        log.info("删除存储源 ID 为 {} 的密码规则 {} 条", storageId, deleteSize);
        return deleteSize;
    }

    /**
     * 监听存储源删除事件，根据存储源 id 删除相关的密码设置
     *
     * @param   storageSourceDeleteEvent
     *          存储源删除事件
     */
    @EventListener
    public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
        Integer storageId = storageSourceDeleteEvent.getId();
        // 通过代理对象调用，确保缓存注解生效
        int updateRows = ((PasswordConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        if (log.isDebugEnabled()) {
            log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源密码设置 {} 条",
                    storageId,
                    storageSourceDeleteEvent.getName(),
                    storageSourceDeleteEvent.getType().getDescription(),
                    updateRows);
        }
    }

    /**
     * 校验文件夹访问密码
     * <p>
     * 处理流程：
     * <ol>
     *   <li>检查当前用户是否有"忽略密码"权限，有则直接通过</li>
     *   <li>获取存储源的所有密码规则</li>
     *   <li>规则为空则直接通过（无需密码）</li>
     *   <li>遍历规则，用表达式匹配请求路径</li>
     *   <li>匹配到规则后：
     *     <ul>
     *       <li>如果未输入密码，返回"需要输入密码"错误</li>
     *       <li>如果密码不匹配，返回"密码错误"</li>
     *       <li>如果密码匹配，返回成功</li>
     *     </ul>
     *   </li>
     *   <li>没有任何规则匹配，直接通过</li>
     * </ol>
     * </p>
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   path
     *          请求路径(全路径，包含用户目录)
     *
     * @param   inputPassword
     *          用户输入的密码
     *
     * @return  校验结果 DTO
     */
    public VerifyResultDTO verifyPassword(Integer storageId, String path, String inputPassword) {
        // 判断是否需要忽略密码校验（有特殊权限的用户可以跳过密码验证）
        boolean isIgnorePassword = userStorageSourceService.hasCurrentUserStorageOperatorPermission(storageId, FileOperatorTypeEnum.IGNORE_PASSWORD);
        if (isIgnorePassword) {
            if (log.isDebugEnabled()) {
                log.debug("权限配置忽略密码校验, 请求路径: {}, 存储源 ID: {}", path, storageId);
            }
            return VerifyResultDTO.success();
        }

        // 获取存储源的所有密码规则
        List<PasswordConfig> passwordConfigList = ((PasswordConfigService) AopContext.currentProxy()).findByStorageId(storageId);

        // 如果规则列表为空, 则表示不需要密码过滤, 直接返回成功
        if (CollectionUtils.isEmpty(passwordConfigList)) {
            if (log.isDebugEnabled()) {
                log.debug("密码规则列表为空, 请求路径: {}, 存储源 ID: {}", path, storageId);
            }
            return VerifyResultDTO.success();
        }

        // 遍历密码规则进行校验
        for (PasswordConfig passwordConfig : passwordConfigList) {
            String expression = passwordConfig.getExpression();
            String expectPassword = passwordConfig.getPassword();

            // 规则表达式为空则跳过
            if (StringUtils.isEmpty(expression)) {
                if (log.isDebugEnabled()) {
                    log.debug("密码规则测试表达式: {}, 请求路径: {}, 表达式为空，跳过该规则比对", expression, path);
                }
                continue;
            }

            try {
                // 判断当前请求路径是否和规则路径表达式匹配（支持 glob 模式）
                boolean match = PatternMatcherUtils.testCompatibilityGlobPattern(expression, path);

                if (log.isDebugEnabled()) {
                    log.debug("密码规则测试表达式: {}, 请求路径: {}, 匹配结果: {}", expression, path, match);
                }

                // 如果匹配则进行密码校验
                if (match) {
                    // 路径匹配但未输入密码
                    if (StringUtils.isEmpty(inputPassword)) {
                        if (log.isDebugEnabled()) {
                            log.debug("密码规则匹配, 但未输入密码；" +
                                            "表达式: {}, 请求路径: {}, 存储源 ID: {}",
                                    expression, path, storageId);
                        }
                        return VerifyResultDTO.fail(ErrorCode.BIZ_STORAGE_SOURCE_FOLDER_PASSWORD_REQUIRED);
                    }

                    // 密码正确
                    if (matchPassword(expectPassword, inputPassword)) {
                        if (log.isDebugEnabled()) {
                            log.debug("密码规则匹配, 密码校验通过；" +
                                            "表达式: {}, 请求路径: {}, 存储源 ID: {}",
                                    expression, path, storageId);
                        }
                        return VerifyResultDTO.success(expression);
                    }

                    // 密码错误
                    if (log.isDebugEnabled()) {
                        log.debug("密码规则匹配, 但输入密码与预期密码不同；" +
                                        "表达式: {}, 请求路径: {}, 存储源 ID: {}",
                                expression, path, storageId);
                    }
                    return VerifyResultDTO.fail(ErrorCode.BIZ_STORAGE_SOURCE_FOLDER_PASSWORD_ERROR);
                }
            } catch (Exception e) {
                log.error("密码规则匹配出现异常，存储源 ID: {}, 异常类型: {}, 拒绝访问，请检查规则配置.",
                        storageId, e.getClass().getSimpleName());
                // A malformed protection rule must not silently turn a protected directory public.
                return VerifyResultDTO.fail(ErrorCode.BIZ_STORAGE_SOURCE_FOLDER_PASSWORD_ERROR);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("校验文件夹密码 path: {}, 没有匹配的表达式, 不进行密码校验.", path);
        }

        // 没有任何规则匹配到，不需要密码
        return VerifyResultDTO.success();
    }


    /**
     * 校验两个密码是否相同, 忽略空白字符
     * <p>先做精确匹配，如果失败则去除所有换行符和首尾空白后再比较。</p>
     *
     * @param   expectedPasswordContent
     *          预期密码
     *
     * @param   password
     *          实际输入的密码
     *
     * @return  是否匹配
     */
    private boolean matchPassword(String expectedPasswordContent, String password) {
        // 先精确匹配
        if (Objects.equals(expectedPasswordContent, password)) {
            return true;
        }

        // 如果预期密码或输入密码为空, 则不匹配
        if (ObjectUtil.hasNull(expectedPasswordContent, password)) {
            return false;
        }

        // 去除换行符和首尾空白后再比较（兼容不同系统下的换行差异）
        expectedPasswordContent = StringUtils.removeAllLineBreaksAndTrim(expectedPasswordContent);
        password = StringUtils.removeAllLineBreaksAndTrim(password);
        return Objects.equals(expectedPasswordContent, password);
    }


    /**
     * 监听存储源复制事件, 复制存储源时, 复制存储源密码设置
     * <p>将源存储源的所有密码配置复制到新创建的存储源中。</p>
     *
     * @param   storageSourceCopyEvent
     *          存储源复制事件
     */
    @EventListener
    public void onStorageSourceCopy(StorageSourceCopyEvent storageSourceCopyEvent) {
        Integer fromId = storageSourceCopyEvent.getFromId();
        Integer newId = storageSourceCopyEvent.getNewId();

        // 获取源存储源的所有密码配置
        List<PasswordConfig> passwordConfigList = ((PasswordConfigService) AopContext.currentProxy()).findByStorageId(fromId);

        // 逐条复制到新存储源
        passwordConfigList.forEach(passwordConfig -> {
            PasswordConfig newPasswordConfig = new PasswordConfig();
            BeanUtils.copyProperties(passwordConfig, newPasswordConfig);
            // 清空 ID 让数据库自动生成，设置新的存储源 ID
            newPasswordConfig.setId(null);
            newPasswordConfig.setStorageId(newId);
            passwordConfigMapper.insert(newPasswordConfig);
        });

        log.info("复制存储源 ID 为 {} 的存储源密码设置到存储源 ID 为 {} 成功, 共 {} 条", fromId, newId, passwordConfigList.size());
    }

}
