package im.tlgxy.cloud.module.storage.context;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.core.exception.biz.InvalidStorageSourceBizException;
import im.tlgxy.cloud.core.util.ClassUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.annotation.StorageParamItem;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceParamDef;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.RefreshTokenService;
import im.tlgxy.cloud.module.storage.support.StorageSourceSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 存储源上下文容器。
 *
 * 这是 TlgxyCloud 存储模块的核心上下文管理类，负责维护所有已初始化的存储源实例及其元数据信息。
 *
 * 核心功能：
 * 1. 缓存已初始化的存储源 Service 实例（按存储源 ID 和存储源 key 索引）；
 * 2. 维护存储类型枚举与 Service 的映射关系；
 * 3. 管理存储源参数定义（用于前端表单渲染）；
 * 4. 负责存储源 Service 的初始化、参数注入和连接测试；
 * 5. 管理 OAuth2 认证相关的 Token 刷新存储源；
 * 6. 支持存储源的销毁（从上下文中移除并清理资源）。
 *
 * 设计思路：
 * - 每个存储源对应一个 {@link AbstractBaseFileService} 的实现类实例（如 S3Service、OneDriveService 等）；
 * - 使用 {@link ConcurrentHashMap} 保证线程安全；
 * - 通过反射和泛型机制自动解析存储源的参数类型并注入配置值；
 * - 所有存储源在系统启动时通过 {@link StorageSourceInitializer} 统一初始化并注册到此上下文中。
 *
 * @author zhaojun
 */
@Slf4j
public class StorageSourceContext {

    /**
     * 存储源 Service 缓存。
     * Map<存储源 ID, 存储源 Service 实例>。
     * 按存储源 ID 索引已初始化的文件服务实例，线程安全。
     */
    private static final Map<Integer, AbstractBaseFileService<IStorageParam>> DRIVES_SERVICE_MAP = new ConcurrentHashMap<>();

    /**
     * 存储源 key 到 ID 的映射缓存。
     * Map<存储源 Key, 存储源 ID>。
     * 用于根据存储源的唯一 key 快速查找对应的存储源 ID。
     */
    private static final Map<String, Integer> STORAGE_KEY_ID_MAP = new ConcurrentHashMap<>();

    /**
     * 存储源类型 bean 名称到 Service 的映射。
     * Map<存储源类型的 Spring bean 名称, 存储源 Service 实例>。
     * 存储的是未初始化的原型（prototype）Service 实例，用于后续初始化。
     */
    private static Map<String, AbstractBaseFileService> storageTypeServiceNameMap;

    /**
     * 存储源类型枚举到 Service 的映射。
     * Map<存储源枚举类型, 存储源 Service 实例>。
     * 方便通过枚举快速查找对应的 Service 实现类。
     */
    private static Map<StorageTypeEnum, AbstractBaseFileService> storageTypeEnumFileServiceMap = new HashMap<>();

    /**
     * 存储源参数类字段缓存。
     * Map<参数类 Class, Map<字段 key, 字段 Field 对象>>。
     * 缓存每个存储源参数类的字段信息，避免重复反射解析，提升性能。
     */
    private static final Map<Class<?>, Map<String, Field>> PARAM_CLASS_FIELD_NAME_MAP_CACHE = new HashMap<>();

    /**
     * 加载所有存储源类型的 Service Bean。
     *
     * 在系统启动时由 {@link StorageSourceInitializer} 调用，将所有 {@link AbstractBaseFileService}
     * 的实现类 Bean 注册到上下文中，建立存储类型枚举与 Service 的映射关系。
     *
     * @param   storageTypeServiceNameMap
     *          从 Spring 容器中获取的所有 AbstractBaseFileService 类型的 Bean，key 为 Bean 名称
     */
    static void load(Map<String, AbstractBaseFileService> storageTypeServiceNameMap) {
        StorageSourceContext.storageTypeServiceNameMap = storageTypeServiceNameMap;
        for (AbstractBaseFileService value : storageTypeServiceNameMap.values()) {
            storageTypeEnumFileServiceMap.put(value.getStorageTypeEnum(), value);
        }
    }

    /**
     * 根据存储源 ID 获取对应的文件服务实例。
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  存储源对应的已初始化的文件服务实例
     *
     * @throws  InvalidStorageSourceBizException
     *          当指定 ID 的存储源不存在或未初始化时抛出
     */
    public static AbstractBaseFileService<IStorageParam> getByStorageId(Integer storageId) {
        AbstractBaseFileService<IStorageParam> abstractBaseFileService = DRIVES_SERVICE_MAP.get(storageId);
        if (abstractBaseFileService == null) {
            throw new InvalidStorageSourceBizException(storageId);
        }
        return abstractBaseFileService;
    }

    /**
     * 根据存储源 key 获取对应的文件服务实例。
     *
     * @param   key
     *          存储源的唯一 key
     *
     * @return  存储源对应的文件服务实例，如果 key 不存在则返回 null
     */
    public static AbstractBaseFileService<?> getByStorageKey(String key) {
        Integer storageId = STORAGE_KEY_ID_MAP.get(key);
        if (storageId == null) {
            return null;
        }
        return getByStorageId(storageId);
    }

    /**
     * 根据存储源类型枚举获取对应的文件服务实例（未初始化的原型）。
     *
     * @param   storageTypeEnum
     *          存储源类型枚举
     *
     * @return  对应类型的文件服务实例
     */
    public static AbstractBaseFileService<?> getByStorageTypeEnum(StorageTypeEnum storageTypeEnum) {
        return storageTypeEnumFileServiceMap.get(storageTypeEnum);
    }

    /**
     * 获取指定存储类型的参数定义列表（用于前端表单渲染）。
     *
     * 遍历所有已注册的 Service 实现，找到与指定类型匹配的第一个 Service，
     * 通过 {@link StorageSourceSupport#getStorageSourceParamList(AbstractBaseFileService)} 获取其参数定义。
     *
     * @param   type
     *          存储类型枚举
     *
     * @return  该类型存储源的参数定义列表，如无匹配则返回空列表
     */
    public static List<StorageSourceParamDef> getStorageSourceParamListByType(StorageTypeEnum type) {
        return storageTypeServiceNameMap.values().stream()
                // 根据存储源类型找到第一个匹配的 Service
                .filter(fileService -> fileService.getStorageTypeEnum() == type)
                .findFirst()
                // 获取该 Service 的参数列表
                .map(StorageSourceSupport::getStorageSourceParamList)
                // 如果没有找到, 则返回空列表
                .orElse(Collections.emptyList());
    }

    /**
     * 初始化指定存储源的 Service 并添加到上下文容器中。
     *
     * 初始化流程：
     * 1. 根据存储类型找到对应的未初始化 Service 原型；
     * 2. 从数据库的配置项中解析存储源参数并注入到参数对象中；
     * 3. 调用 Service 的 init 方法进行初始化；
     * 4. 调用 testConnection 方法测试与存储源的连接；
     * 5. 将初始化完成的 Service 注册到缓存中。
     *
     * @param   storageSourceInitDTO
     *          存储源初始化数据传输对象，包含存储源 ID、key、类型和配置参数
     */
    public static void init(StorageSourceInitDTO storageSourceInitDTO) {
        Integer storageId = storageSourceInitDTO.getId();
        String storageName = storageSourceInitDTO.getName();
        String key = storageSourceInitDTO.getKey();
        StorageTypeEnum storageTypeEnum = storageSourceInitDTO.getType();

        // 获取对应存储类型的未初始化 Service Bean
        AbstractBaseFileService<IStorageParam> baseFileService = getInitStorageBeanByStorageType(storageTypeEnum);
        if (baseFileService == null) {
            throw new InvalidStorageSourceBizException(storageId);
        }

        // 填充初始化参数：从数据库配置项中解析并注入到参数对象
        IStorageParam initParam = getInitParam(baseFileService, storageSourceInitDTO.getStorageSourceConfigList());

        // 进行初始化并测试与存储源的连接
        baseFileService.init(storageName, storageId, initParam);
        baseFileService.testConnection();

        // 注册到缓存中
        DRIVES_SERVICE_MAP.put(storageId, baseFileService);
        STORAGE_KEY_ID_MAP.put(key, storageId);
    }

    /**
     * 获取指定存储源类型的未初始化 Service Bean（原型实例）。
     *
     * 从 Spring 容器中获取一个新的 Bean 实例（prototype），避免多个存储源共享同一个 Service 实例。
     *
     * @param   storageTypeEnum
     *          存储源类型枚举
     *
     * @return  未初始化的 Service 实例，如果没有找到匹配的类型则返回 null
     */
    private static AbstractBaseFileService<IStorageParam> getInitStorageBeanByStorageType(StorageTypeEnum storageTypeEnum) {
        for (AbstractBaseFileService<?> value : storageTypeServiceNameMap.values()) {
            if (Objects.equals(value.getStorageTypeEnum(), storageTypeEnum)) {
                return SpringUtil.getBean(value.getClass());
            }
        }
        return null;
    }

    /**
     * 获取指定存储源的初始化参数对象。
     *
     * 通过反射机制解析存储源 Service 实现类的泛型参数类型（实现 {@link IStorageParam} 接口的参数类），
     * 然后将数据库中的配置项值注入到参数对象的对应字段中。
     *
     * 参数注入逻辑：
     * 1. 通过反射获取参数字段，建立 key -> Field 的映射；
     * 2. 遍历数据库配置项，将值设置到对应的字段上；
     * 3. 对于数据库中没有配置的字段，使用注解中定义的默认值填充；
     * 4. 标记了 ignoreInput 的字段不会被注入。
     *
     * @param   baseFileService
     *          存储源 Service 实例
     *
     * @param   storageSourceConfigList
     *          从数据库读取的存储源配置项列表
     *
     * @return  填充好配置值的参数对象
     */
    private static IStorageParam getInitParam(AbstractBaseFileService<?> baseFileService, List<StorageSourceConfig> storageSourceConfigList) {
        // 获取存储源实现类的实际 Class（处理 AOP 代理）
        Class<?> beanTargetClass = AopUtils.getTargetClass(baseFileService);
        // 获取存储源实现类的第一个泛型参数类型，即参数类
        Class<?> paramClass = ClassUtils.getClassFirstGenericsParam(beanTargetClass);

        // 获取存储器参数 key -> 存储器 field 对照关系，如果缓存中有，则从缓存中取
        Map<String, Field> fieldMap = new HashMap<>();
        if (PARAM_CLASS_FIELD_NAME_MAP_CACHE.containsKey(paramClass)) {
            fieldMap = PARAM_CLASS_FIELD_NAME_MAP_CACHE.get(paramClass);
        } else {
            Field[] fields = ReflectUtil.getFieldsDirectly(paramClass, true);
            List<String> ignoreFieldNameList = new ArrayList<>();
            for (Field field : fields) {
                String key;

                StorageParamItem storageParamItem = field.getDeclaredAnnotation(StorageParamItem.class);
                // 没有注解或注解中没有配置 key 则使用字段名作为 key
                if (storageParamItem == null || StringUtils.isEmpty(storageParamItem.key())) {
                    key = field.getName();
                } else {
                    key = storageParamItem.key();
                }

                // 记录需要忽略（不对外暴露）的字段
                if (storageParamItem != null && storageParamItem.ignoreInput()) {
                    ignoreFieldNameList.add(key);
                }

                // 如果 map 中已包含此 key（可能是父类的），则跳过（子类优先）
                if (fieldMap.containsKey(key)) {
                    continue;
                }

                if (!ignoreFieldNameList.contains(key)) {
                    fieldMap.put(key, field);
                }
            }
            // 缓存解析结果，避免重复反射
            PARAM_CLASS_FIELD_NAME_MAP_CACHE.put(paramClass, fieldMap);
        }

        // 通过反射实例化参数对象
        IStorageParam iStorageParam = ReflectUtil.newInstance(paramClass.getName());

        Map<String, Field> fieldMapCopy = new HashMap<>(fieldMap);

        // 遍历数据库配置项，给所有字段填充值
        for (StorageSourceConfig storageSourceConfig : storageSourceConfigList) {
            String name = storageSourceConfig.getName();
            String value = storageSourceConfig.getValue();
            try {
                Field field = fieldMap.get(name);
                ReflectUtil.setFieldValue(iStorageParam, field, value);
                fieldMapCopy.remove(name); // 标记为已填充
            } catch (Exception e) {
                log.warn("存储源 {} 从数据库获取存储源参数进行初始化时为字段 {} 初始化值 {} 失败", baseFileService, name, value, e);
            }
        }

        // 对于数据库中没有配置的字段，使用默认值填充
        if (!fieldMapCopy.isEmpty()) {
            List<StorageSourceParamDef> storageSourceParamList = StorageSourceSupport.getStorageSourceParamList(baseFileService);
            Map<String, StorageSourceParamDef> storageSourceParamDefMap = storageSourceParamList.stream()
                    .collect(Collectors.toMap(StorageSourceParamDef::getKey, Function.identity()));

            for (Map.Entry<String, Field> entry : fieldMapCopy.entrySet()) {
                Field field = entry.getValue();
                StorageSourceParamDef storageSourceParamDef = storageSourceParamDefMap.get(entry.getKey());
                if (storageSourceParamDef == null) {
                    continue;
                }

                String defaultValue = storageSourceParamDef.getDefaultValue();
                if (StringUtils.isBlank(defaultValue)) {
                    continue;
                }
                ReflectUtil.setFieldValue(iStorageParam, field, defaultValue);
                if (log.isDebugEnabled()) {
                    log.debug("存储源 {} 数据库未设置字段 {} 值，使用默认值 {}", baseFileService, entry.getKey(), defaultValue);
                }
            }
        }

        return iStorageParam;
    }

    /**
     * 获取所有需要刷新 AccessToken 的存储源。
     *
     * 遍历所有已初始化的存储源，筛选出实现了 {@link RefreshTokenService} 接口且初始化成功的存储源。
     * 这些存储源通常使用 OAuth2 认证（如 Google Drive、OneDrive 等），需要定期刷新 accessToken。
     *
     * @return  Map<存储源 ID, RefreshTokenService 实例>，key 为存储源 ID，value 为可刷新 Token 的服务实例
     */
    public static Map<Integer, RefreshTokenService> getAllRefreshTokenStorageSource() {
        Map<Integer, RefreshTokenService> result = new HashMap<>();

        for (Map.Entry<Integer, AbstractBaseFileService<IStorageParam>> baseFileServiceEntry : DRIVES_SERVICE_MAP.entrySet()) {
            Integer storageId = baseFileServiceEntry.getKey();
            AbstractBaseFileService<?> baseFileService = baseFileServiceEntry.getValue();
            // 如果未初始化成功, 则直接跳过
            if (BooleanUtils.isNotTrue(baseFileService.isInitialized())) {
                continue;
            }

            if (baseFileService instanceof RefreshTokenService) {
                result.put(storageId, (RefreshTokenService) baseFileService);
            }
        }

        return result;
    }

    /**
     * 销毁指定存储源的 Service 实例，从上下文中移除并释放资源。
     *
     * 当存储源被删除或禁用时调用，会从缓存中移除该存储源的 Service 实例，
     * 并调用 Service 的 destroy 方法释放其占用的资源（如连接池、文件句柄等）。
     *
     * @param   storageSource
     *          要销毁的存储源实体
     */
    public static void destroy(StorageSource storageSource) {
        Integer id = storageSource.getId();
        String key = storageSource.getKey();
        log.info("清理存储源上下文对象, storageId: {}, storageKey: {}", id, key);

        // 从缓存中移除并调用 destroy 释放资源
        AbstractBaseFileService<IStorageParam> abstractBaseFileService = DRIVES_SERVICE_MAP.remove(id);
        if (abstractBaseFileService != null) {
            abstractBaseFileService.destroy();
        }

        // 移除 key 到 ID 的映射
        STORAGE_KEY_ID_MAP.remove(key);
    }

}
