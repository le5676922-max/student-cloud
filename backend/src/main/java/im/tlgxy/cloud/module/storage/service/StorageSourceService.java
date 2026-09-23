package im.tlgxy.cloud.module.storage.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ReflectUtil;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.StrPool;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.password.model.dto.VerifyResultDTO;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.readme.model.entity.ReadmeConfig;
import im.tlgxy.cloud.module.readme.service.ReadmeConfigService;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.convert.StorageSourceConvert;
import im.tlgxy.cloud.module.storage.event.StorageSourceCopyEvent;
import im.tlgxy.cloud.module.storage.event.StorageSourceDeleteEvent;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceDTO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.model.request.admin.CopyStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.request.admin.UpdateStorageSortRequest;
import im.tlgxy.cloud.module.storage.model.request.base.FileListConfigRequest;
import im.tlgxy.cloud.module.storage.model.request.base.SaveStorageSourceRequest;
import im.tlgxy.cloud.module.storage.model.result.StorageSourceConfigResult;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 存储源基本信息 Service
 * <p>
 * 负责管理存储源的核心业务逻辑。
 * 包括存储源的增删改查、排序调整、复制操作，
 * 以及与存储源参数配置、用户权限、密码验证、readme 文档等模块的协调。
 * </p>
 * <p>
 * 主要功能：
 * - 存储源基本信息的 CRUD 操作
 * - 根据用户权限获取可访问的存储源列表
 * - 存储源的初始化、销毁和连接测试
 * - 存储源排序值的批量更新
 * - 存储源的完整保存（含参数配置的保存和初始化）
 * - 存储源的复制（含参数配置的复制和初始化）
 * - 存储源配置信息的获取（含密码验证和 readme 信息）
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Service
@CacheConfig(cacheNames = "storageSource")
public class StorageSourceService {

    @Resource
    private StorageSourceMapper storageSourceMapper;

    @Resource
    private StorageSourceConvert storageSourceConvert;

    @Resource
    private StorageSourceConfigService storageSourceConfigService;

    @Resource
    private PasswordConfigService passwordConfigService;

    @Resource
    private UserStorageSourceService userStorageSourceService;

    @Resource
    private ReadmeConfigService readmeConfigService;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    @Resource
    private TlgxyCloudCacheManager studentCloudCacheManager;

    @Resource
    private TenantSecurityService tenantSecurityService;

    @Resource
    private TenantService tenantService;


    /**
     * 获取所有存储源列表，按排序值升序排列
     *
     * @return 所有存储源列表
     */
    public List<StorageSource> findAllOrderByOrderNum() {
        return storageSourceMapper.findAllOrderByOrderNum();
    }

    public List<StorageSource> findManageableOrderByOrderNum() {
        tenantSecurityService.requireSystemAdmin();
        return storageSourceMapper.findAllOrderByOrderNum();
    }


    /**
     * 获取当前登录用户可访问的已启用存储源列表
     * <p>
     * 如果用户 ID 为 null，则返回空列表。
     * 结果通过缓存管理器获取，支持用户级别的缓存。
     * </p>
     *
     * @param   userId
     *          当前登录用户 ID
     *
     * @return 用户可访问的已启用存储源列表
     */
    public List<StorageSource> findAllEnableOrderByOrderNum(Integer userId) {
        // 用户 ID 为空时返回空列表（未登录用户）
        if (userId == null) {
            return Collections.emptyList();
        }
        // 通过缓存管理器获取，减少数据库查询
        return studentCloudCacheManager.findAllEnableOrderByOrderNum(userId,
                userEnableStorageSourceId -> storageSourceMapper.findUserEnableList(userEnableStorageSourceId)
        );
    }

    /**
     * 根据 ID 获取存储源信息
     * <p>
     * 结果会被缓存，缓存 Key 为 id。
     * </p>
     *
     * @param   id
     *          存储源 ID
     *
     * @return  存储源实体，未找到时返回 null
     */
    @Cacheable(key = "#id", unless = "#result == null", condition = "#id != null")
    public StorageSource findById(Integer id) {
        return storageSourceMapper.selectById(id);
    }


    /**
     * 根据存储源别名（key）获取存储源信息
     * <p>
     * 结果会被缓存，缓存 Key 为 storageKey。
     * </p>
     *
     * @param   storageKey
     *          存储源别名（key）
     *
     * @throws InvalidStorageSourceBizException   存储源不存在时，抛出业务异常
     *
     * @return  存储源实体，未找到时返回 null
     */
    @Cacheable(key = "#storageKey", unless = "#result == null", condition = "#storageKey != null")
    public StorageSource findByStorageKey(String storageKey) {
        return storageSourceMapper.findByStorageKey(storageKey);
    }


    /**
     * 根据存储源别名（key）清除缓存
     *
     * @param   storageKey
     *          存储源别名（key）
     */
    @CacheEvict(key = "#storageKey")
    public void clearCacheByStorageKey(String storageKey) {}


    /**
     * 根据存储源别名（key）获取存储源 ID
     * <p>
     * 通过 AOP 代理调用缓存方法，确保缓存生效。
     * </p>
     *
     * @param   storageKey
     *          存储源别名（key）
     *
     * @return  存储源 ID，未找到时返回 null
     */
    public Integer findIdByKey(String storageKey) {
        return Optional.ofNullable(((StorageSourceService) AopContext.currentProxy()).findByStorageKey(storageKey)).map(StorageSource::getId).orElse(null);
    }


    /**
     * 根据存储源 ID 获取存储源别名（key）
     * <p>
     * 通过 AOP 代理调用缓存方法，确保缓存生效。
     * </p>
     *
     * @param   id
     *          存储源 ID
     *
     * @return  存储源别名（key），未找到时返回 null
     */
    public String findStorageKeyById(Integer id){
        return Optional.ofNullable(((StorageSourceService)AopContext.currentProxy()).findById(id)).map(StorageSource::getKey).orElse(null);
    }


    /**
     * 根据 ID 获取存储源的类型
     * <p>
     * 通过 AOP 代理调用缓存方法，确保缓存生效。
     * </p>
     *
     * @param   id
     *          存储源 ID
     *
     * @return  存储源对应的类型枚举，未找到时返回 null
     */
    public StorageTypeEnum findStorageTypeById(Integer id) {
        return Optional.ofNullable(((StorageSourceService)AopContext.currentProxy()).findById(id)).map(StorageSource::getType).orElse(null);
    }


    /**
     * 获取指定存储源的完整 DTO 对象
     * <p>
     * 此方法会将数据库中存储的键值对参数通过反射写入到
     * StorageSourceAllParamDTO 对象中，然后与基本存储源信息合并为 DTO。
     * 结果会被缓存，缓存 Key 为 'dto-' + id。
     * </p>
     *
     * @param   id
     *          存储源 ID
     *
     * @return  包含详细参数配置的存储源 DTO 对象
     */
    public StorageSourceDTO findDTOById(Integer id) {
        tenantSecurityService.requireManageStorage(id);
        // 将参数列表通过反射写入到 StorageSourceAllParam DTO 中
        StorageSourceAllParamDTO storageSourceAllParam = new StorageSourceAllParamDTO();
        for (StorageSourceConfig storageSourceConfig : storageSourceConfigService.selectStorageConfigByStorageId(id)) {
            // 检查 DTO 中是否存在对应字段
            if (ReflectUtil.hasField(StorageSourceAllParamDTO.class, storageSourceConfig.getName())) {
                ReflectUtil.setFieldValue(storageSourceAllParam, storageSourceConfig.getName(), storageSourceConfig.getValue());
            } else {
                // 数据库中存在 DTO 没有的字段，记录警告日志
                log.warn("数据库中存储源 {} 参数 {} 不存在于存储源参数 DTO 中, 请检查参数名是否正确.", id, storageSourceConfig.getName());
            }
        }
        // 获取数据库基本对象，转为包含参数的 DTO 对象返回
        StorageSource storageSource = findById(id);
        return storageSourceConvert.entityToDTO(storageSource, storageSourceAllParam);
    }


    /**
     * 判断存储源别名（key）是否已存在
     * <p>
     * 不读取缓存，直接查询数据库确保结果的实时性。
     * </p>
     *
     * @param   storageKey
     *          存储源别名（key）
     *
     * @return  true 表示已存在，false 表示不存在
     */
    public boolean existByStorageKey(String storageKey) {
        return ((StorageSourceService)AopContext.currentProxy()).findByStorageKey(storageKey) != null;
    }


    /**
     * 删除指定存储源，会级联删除其参数配置
     * <p>
     * 删除流程：
     * 1. 清除相关缓存
     * 2. 检查存储源是否存在
     * 3. 发布存储源删除事件（触发参数配置的级联删除）
     * 4. 执行数据库删除
     * 5. 销毁存储源上下文
     * 6. 清除用户存储源缓存
     * </p>
     *
     * @param   id
     *          存储源 ID
     * @return  被删除的存储源实体
     */
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(key = "#id"),
            @CacheEvict(key = "'dto-' + #id"),
            @CacheEvict(key = "#result.key", condition = "#result != null")
    })
    public StorageSource deleteById(Integer id) {
        tenantSecurityService.requireManageStorage(id);
        log.info("删除 id 为 {} 的存储源", id);
        // 查询存储源是否存在
        StorageSource storageSource = ((StorageSourceService)AopContext.currentProxy()).findById(id);

        if (storageSource == null) {
            throw new BizException(ErrorCode.BIZ_STORAGE_NOT_FOUND);
        }

        // 发布删除事件，触发关联数据的删除（参数配置等）
        StorageSourceDeleteEvent storageSourceDeleteEvent = new StorageSourceDeleteEvent(storageSource);
        applicationEventPublisher.publishEvent(storageSourceDeleteEvent);

        // 执行数据库删除
        int deleteEntitySize = storageSourceMapper.deleteById(id);

        // 销毁存储源上下文，释放资源
        StorageSourceContext.destroy(storageSource);
        log.info("删除存储源 {} 成功, 影响行数: {}", id, deleteEntitySize);
        // 清除用户存储源缓存
        studentCloudCacheManager.clearUserEnableStorageSourceCache();
        return storageSource;
    }


    /**
     * 批量更新存储源排序值
     * <p>
     * 遍历排序请求列表，当目标排序值与当前位置不一致时才更新数据库。
     * 使用事务保证操作的原子性。更新后清除所有存储源缓存。
     * </p>
     *
     * @param   updateStorageSortRequestList
     *          包含存储源 ID 及新排序值的请求列表
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(allEntries = true)
    public void updateStorageSort(List<UpdateStorageSortRequest> updateStorageSortRequestList) {
        for (int i = 0; i < updateStorageSortRequestList.size(); i++) {
            UpdateStorageSortRequest item = updateStorageSortRequestList.get(i);
            tenantSecurityService.requireManageStorage(item.getId());
            // 只有当排序值发生变化时才更新数据库，减少不必要的写操作
            if (!Objects.equals(i, item.getOrderNum())) {
                log.info("变更存储源 {} 顺序号为 {}", item.getId(), i);
                storageSourceMapper.updateSetOrderNumById(i, item.getId());
            }
        }
    }


    /**
     * 根据 ID 更新存储源基本信息
     * <p>
     * 更新前会清除存储源相关缓存（按 id、key、dto-id）。
     * </p>
     *
     * @param entity 包含更新后信息的存储源实体
     */
    @Caching(evict = {
            @CacheEvict(key = "#entity.id"),
            @CacheEvict(key = "#entity.key"),
            @CacheEvict(key = "'dto-' + #entity.id")
    })
    public void updateById(StorageSource entity) {
        StorageSource original = tenantSecurityService.requireManageStorage(entity.getId());
        entity.setTenantId(original.getTenantId());
        entity.setOwnerUserId(original.getOwnerUserId());
        storageSourceMapper.updateById(entity);
        studentCloudCacheManager.clearUserEnableStorageSourceCache();
    }


    /**
     * 保存存储源基本信息及其对应的参数配置
     * <p>
     * 完整的保存流程：
     * 1. 判断是新增还是更新操作
     * 2. 转换为实体对象
     * 3. 如果是更新，先销毁旧的存储源上下文
     * 4. 保存或更新存储源基本信息
     * 5. 保存存储源的参数配置
     * 6. 初始化存储源上下文（加载驱动、测试连接等）
     * 7. 如果是新增，为用户分配默认权限
     * </p>
     *
     * @param   saveStorageSourceRequest
     *          包含存储源基本信息和扩展参数的请求对象
     * @return  保存后的存储源 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer saveStorageSource(SaveStorageSourceRequest saveStorageSourceRequest) {
        tenantSecurityService.requireSystemAdmin();
        // 判断是新增（无 ID）还是更新（有 ID）
        boolean isSave = ObjUtil.isEmpty(saveStorageSourceRequest.getId());

        log.info("尝试保存存储源, id: {}, name: {}, key: {}, type: {}",
                saveStorageSourceRequest.getId(), saveStorageSourceRequest.getName(),
                saveStorageSourceRequest.getKey(), saveStorageSourceRequest.getType().getDescription());

        // 转换为存储源 entity 对象
        StorageSource storageSource = storageSourceConvert.saveRequestToEntity(saveStorageSourceRequest);
        storageSource.setSearchMode(SearchModeEnum.SEARCH_ALL_MODE);
        User operator = tenantSecurityService.currentUser();

        // 如果是更新操作，则销毁之前的存储源上下文（因为参数可能已经改变）
        if (isSave) {
            Integer tenantId = Optional.ofNullable(saveStorageSourceRequest.getTenantId())
                    .orElse(UserConstant.DEFAULT_TENANT_ID);
            tenantService.getEnabledById(tenantId);
            storageSource.setTenantId(tenantId);
            storageSource.setOwnerUserId(operator.getId());
        } else {
            StorageSource dbStorageSource = tenantSecurityService.requireManageStorage(
                    saveStorageSourceRequest.getId());
            storageSource.setTenantId(dbStorageSource.getTenantId());
            storageSource.setOwnerUserId(dbStorageSource.getOwnerUserId());
            StorageSourceContext.destroy(dbStorageSource);
        }

        // 保存或更新存储源基本信息
        StorageSource dbSaveResult = ((StorageSourceService)AopContext.currentProxy()).saveOrUpdate(storageSource);

        log.info("保存存储源成功, id: {}, name: {}, key: {}, type: {}",
                dbSaveResult.getId(), dbSaveResult.getName(),
                dbSaveResult.getKey(), dbSaveResult.getType().getDescription());

        // 获取保存后的存储源 ID
        Integer storageId = dbSaveResult.getId();

        // 将前端提交的参数 DTO 转换为配置实体列表并保存
        List<StorageSourceConfig> storageSourceConfigList =
                storageSourceConfigService.storageSourceAllParamToConfigList(storageId,
                                                                    dbSaveResult.getType(),
                                                                    saveStorageSourceRequest.getStorageSourceAllParam());
        storageSourceConfigService.saveBatch(storageId, storageSourceConfigList);
        log.info("保存存储源参数成功，尝试根据参数初始化存储源, id: {}, name: {}, config size: {}",
                dbSaveResult.getId(), dbSaveResult.getName(), storageSourceConfigList.size());

        // 初始化存储源上下文（加载对应的文件系统驱动并测试连接）
        StorageSourceInitDTO storageSourceInitDTO = StorageSourceInitDTO.convert(dbSaveResult, storageSourceConfigList);
        StorageSourceContext.init(storageSourceInitDTO);
        log.info("根据参数初始化存储源成功, id: {}, name: {}, config size: {}",
                dbSaveResult.getId(), dbSaveResult.getName(), storageSourceConfigList.size());


        // 新存储源只分配给同一工作空间的用户和系统管理员
        if (isSave) {
            userStorageSourceService.addDefaultPermissionsForTenantUsersInStorageSource(
                    storageId,
                    dbSaveResult.getTenantId()
            );
        }

        return storageId;
    }


    /**
     * 保存或更新存储源基本信息
     * <p>
     * 如果没有 ID 则新增，有 ID 则更新。
     * 如果更新时修改了别名（key），会清除旧别名的缓存。
     * 如果没有输入别名，则自动将 ID 设置为别名。
     * </p>
     *
     * @param   storageSource
     *          存储源实体对象
     *
     * @return  保存后的存储源实体（包含生成的 ID）
     */
    @Caching(evict = {
            @CacheEvict(key = "#result.id"),
            @CacheEvict(key = "'dto-' + #result.id"),
            @CacheEvict(key = "#result.key")
    })
    public StorageSource saveOrUpdate(StorageSource storageSource) {
        // 判断是新增还是更新
        if (storageSource.getId() == null) {
            // 无 ID 则为新增
            storageSourceMapper.insert(storageSource);
        } else {
            // 有 ID 则为更新，判断是否修改了别名（key）
            StorageSource originStorageSource = storageSourceMapper.selectById(storageSource.getId());
            if (!StringUtils.equals(originStorageSource.getKey(), storageSource.getKey())) {
                // 别名变更了，清除旧别名的缓存
                ((StorageSourceService)AopContext.currentProxy()).clearCacheByStorageKey(originStorageSource.getKey());
            }
            storageSourceMapper.updateById(storageSource);
        }

        // 如果没输入存储源别名(key), 则自动将 ID 设置为别名
        if (StringUtils.isEmpty(storageSource.getKey()) && !StringUtils.equals(storageSource.getId().toString(), storageSource.getKey())) {
            storageSource.setKey(Convert.toStr(storageSource.getId()));
            storageSourceMapper.updateById(storageSource);
        }
        // 清除用户存储源缓存
        studentCloudCacheManager.clearUserEnableStorageSourceCache();
        return storageSource;
    }


    /**
     * 获取存储源的前端配置信息
     * <p>
     * 根据请求中提供的存储源 key 和路径，返回前端所需的配置信息，
     * 包括 readme 文档内容和显示模式、用户权限、存储源元数据和根路径等。
     * </p>
     * <p>
     * 如果目标路径有密码保护且密码验证失败，则不返回 readme 信息。
     * </p>
     *
     * @param   fileListConfigRequest
     *          包含存储源 key、路径和密码的请求参数
     *
     * @return  存储源配置响应对象，包含 readme、权限、元数据等信息
     *
     * @throws  InvalidStorageSourceBizException
     *          如果存储源不存在
     */
    public StorageSourceConfigResult getStorageConfigSource(FileListConfigRequest fileListConfigRequest) {
        String storageKey = fileListConfigRequest.getStorageKey();

        // 判断存储源是否存在
        StorageSource storageSource = ((StorageSourceService)AopContext.currentProxy()).findByStorageKey(storageKey);
        if (storageSource == null) {
            throw new InvalidStorageSourceBizException(storageKey);
        }

        // 根据存储源 key 获取存储源 ID
        Integer storageId = storageSource.getId();

        // 拼接用户目录，获取完整路径
        AbstractBaseFileService<IStorageParam> baseFileService = StorageSourceContext.getByStorageId(storageId);
        String fullPath = StringUtils.concat(baseFileService.getCurrentUserBasePath(), fileListConfigRequest.getPath());

        // 验证文件夹密码
        VerifyResultDTO verifyPassword = passwordConfigService.verifyPassword(storageId, fullPath, fileListConfigRequest.getPassword());

        ReadmeConfig readmeByPath = null;
        if (verifyPassword.isPassed()) {
            // 密码验证通过，获取指定存储源路径下的 readme 配置信息
            readmeByPath = readmeConfigService.getByStorageAndPath(storageId, fileListConfigRequest.getPath(), storageSource.getCompatibilityReadme());
        } else {
            log.info("文件夹密码验证失败，不获取 readme 信息, storageId: {}, path: {}, password: {}", storageId, fullPath, fileListConfigRequest.getPassword());
        }

        // 转换为配置响应对象
        StorageSourceConfigResult storageSourceConfigResult = storageSourceConvert.entityToConfigResult(storageSource, readmeByPath);

        // 获取当前用户对该存储源的操作权限
        HashMap<String, Boolean> permissionMap = userStorageSourceService.getCurrentUserPermissionMapByStorageId(storageId);
        storageSourceConfigResult.setPermission(permissionMap);

        // 获取存储源元数据（如文件系统信息）
        storageSourceConfigResult.setMetadata(baseFileService.getStorageSourceMetadata());

        // 获取用户在该存储源中的根路径
        UserStorageSource userStorageSource = userStorageSourceService.getByUserIdAndStorageId(TlgxyCloudAuthUtil.getCurrentUserId(), storageId);
        if (userStorageSource == null) {
            storageSourceConfigResult.setRootPath(StrPool.SLASH);
        } else {
            storageSourceConfigResult.setRootPath(userStorageSource.getRootPath());
        }
        return storageSourceConfigResult;
    }

    /**
     * 复制存储源
     * <p>
     * 复制流程：
     * 1. 检查目标别名是否已存在
     * 2. 检查复制源是否存在
     * 3. 复制基本存储源信息（使用新的名称和别名）
     * 4. 发布复制事件（触发参数配置的复制）
     * 5. 初始化新存储源的上下文
     * </p>
     *
     * @param   copyStorageSourceRequest
     *          包含源存储源 ID、新名称和新别名的请求参数
     *
     * @return  新创建的存储源 ID
     *
     * @throws  BizException
     *          如果目标别名已存在
     *
     * @throws  InvalidStorageSourceBizException
     *          如果源存储源不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer copy(CopyStorageSourceRequest copyStorageSourceRequest) {
        // 检查目标存储源别名是否已存在
        String toKey = copyStorageSourceRequest.getToKey();
        boolean existByStorageKey = ((StorageSourceService)AopContext.currentProxy()).existByStorageKey(toKey);
        if (existByStorageKey) {
            throw new BizException(ErrorCode.BIZ_STORAGE_KEY_EXIST);
        }

        // 检查复制源是否存在
        Integer fromStorageId = copyStorageSourceRequest.getFromId();
        StorageSource storageSource = tenantSecurityService.requireManageStorage(fromStorageId);

        // 复制基本存储源信息
        StorageSource newStorageSource = new StorageSource();
        BeanUtils.copyProperties(storageSource, newStorageSource);
        newStorageSource.setId(null);  // 清除 ID，让数据库自动生成
        newStorageSource.setKey(copyStorageSourceRequest.getToKey());
        newStorageSource.setName(copyStorageSourceRequest.getToName());
        newStorageSource.setOwnerUserId(tenantSecurityService.currentUser().getId());

        // 保存新存储源
        StorageSource dbSaveResult = ((StorageSourceService)AopContext.currentProxy()).saveOrUpdate(newStorageSource);
        Integer newStorageId = dbSaveResult.getId();
        log.info("复制存储源成功，源 [id: {}, name: {}, key: {}], 复制后 [id: {}, name: {}, key: {}, type: {}]",
                fromStorageId, storageSource.getName(), storageSource.getKey(),
                newStorageId, dbSaveResult.getName(),
                dbSaveResult.getKey(), dbSaveResult.getType().getDescription());

        // 发布复制事件，触发参数配置的复制
        StorageSourceCopyEvent storageSourceCopyEvent = new StorageSourceCopyEvent(fromStorageId, newStorageId);
        applicationEventPublisher.publishEvent(storageSourceCopyEvent);

        // 初始化新存储源的上下文
        List<StorageSourceConfig> storageSourceConfigList = storageSourceConfigService.selectStorageConfigByStorageId(newStorageId);
        StorageSourceInitDTO storageSourceInitDTO = StorageSourceInitDTO.convert(dbSaveResult, storageSourceConfigList);
        StorageSourceContext.init(storageSourceInitDTO);
        log.info("初始化存储源成功, id: {}, name: {}, config size: {}",
                newStorageId, dbSaveResult.getName(), storageSourceConfigList.size());

        return newStorageId;
    }

}
