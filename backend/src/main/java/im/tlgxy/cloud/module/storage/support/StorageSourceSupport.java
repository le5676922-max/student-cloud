package im.tlgxy.cloud.module.storage.support;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.core.util.*;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.storage.annotation.StorageParamItem;
import im.tlgxy.cloud.module.storage.annotation.StorageParamSelect;
import im.tlgxy.cloud.module.storage.annotation.StorageParamSelectOption;
import im.tlgxy.cloud.module.storage.enums.StorageParamItemAnnoEnum;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceParamDef;
import im.tlgxy.cloud.module.storage.model.enums.StorageParamTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.IStorageParam;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储源参数支持类 —— 负责解析和缓存各存储源实现类的配置参数定义.
 * <p>
 * 该类是存储源参数系统的核心工具类，主要功能包括：
 * <ul>
 *   <li>通过反射扫描存储源实现类上的 {@link StorageParamItem} 注解，提取参数定义</li>
 *   <li>解析注解中的各种属性（如名称、描述、是否必填、默认值、选项列表等）</li>
 *   <li>将解析结果缓存到 {@link ConcurrentHashMap} 中，避免重复反射操作</li>
 *   <li>支持参数属性的覆盖（overwrite）机制，允许子类选择性覆盖父类的参数定义</li>
 *   <li>按照 {@code order} 属性对参数列表进行排序</li>
 * </ul>
 * </p>
 * <p>
 * 使用场景：当前端需要展示存储源的配置表单时，通过此类获取该存储源类型的所有参数定义，
 * 从而动态生成配置界面，无需为每种存储源硬编码表单字段。
 * </p>
 *
 * @author zhaojun
 */
public class StorageSourceSupport {

    /**
     * 存储源类型与参数定义列表的缓存映射.
     * <p>
     * Key: 存储源实现类的 Class 对象
     * Value: 该存储源对应的参数定义列表（按 order 排序后）
     * 使用 {@link ConcurrentHashMap} 保证线程安全，同时避免每次请求都进行反射解析。
     * </p>
     */
    private static final Map<Class<? extends AbstractBaseFileService>, List<StorageSourceParamDef>> STORAGE_SOURCE_PARAM_CACHE = new ConcurrentHashMap<>();

    /**
     * 获取指定存储源实例的所有参数列表定义.
     * <p>
     * 该方法首先检查缓存中是否已有该存储源类型的参数定义，如果有则直接返回缓存结果。
     * 否则，通过反射扫描存储源参数类的所有字段，提取 {@link StorageParamItem} 注解信息，
     * 构建参数定义列表，缓存后返回。
     * </p>
     * <p>
     * 处理流程：
     * <ol>
     *   <li>处理 Spring AOP 代理，获取真实的目标类</li>
     *   <li>检查缓存，命中则直接返回</li>
     *   <li>获取存储源实现类的第一个泛型参数类型（即参数POJO类）</li>
     *   <li>反射获取参数类的所有字段并遍历</li>
     *   <li>对每个标注了 {@link StorageParamItem} 的字段，提取注解属性并构建参数定义</li>
     *   <li>处理参数的覆盖（overwrite）逻辑</li>
     *   <li>按 order 排序后返回</li>
     * </ol>
     * </p>
     *
     * @param abstractBaseFileService 存储源服务实现类实例
     * @return 该存储源对应的参数定义列表，按 order 升序排列
     */
    public static List<StorageSourceParamDef> getStorageSourceParamList(AbstractBaseFileService abstractBaseFileService) {
        // 处理 Spring AOP 代理，获取真实的目标类（而非代理类）
        Class<? extends AbstractBaseFileService> clazz;
        if (AopUtils.isAopProxy(abstractBaseFileService)) {
            clazz = (Class<? extends AbstractBaseFileService>) AopUtils.getTargetClass(abstractBaseFileService);
        } else {
            clazz = abstractBaseFileService.getClass();
        }
        // 获取存储源实例的当前参数值，用于后续动态选项的获取
        IStorageParam storageParam = abstractBaseFileService.getParam();
        // 如果缓存中有，则直接返回，避免重复解析
        if (STORAGE_SOURCE_PARAM_CACHE.containsKey(clazz)) {
            return STORAGE_SOURCE_PARAM_CACHE.get(clazz);
        }

        // 用于存储字段名到参数定义的映射，方便后续处理覆盖逻辑
        Map<String, StorageSourceParamDef> storageSourceParamDefMap = new HashMap<>();

        // 获取存储源实现类的第一个泛型参数类型，即参数 POJO 的 Class
        Class<?> paramClass = ClassUtils.getClassFirstGenericsParam(clazz);
        // 反射获取参数类的所有字段
        Field[] fields = ReflectUtil.getFields(paramClass);

        // 记录已处理的字段名列表，用于去重
        List<String> useFieldNames = new ArrayList<>();

        // 记录字段覆盖映射：字段名 -> 该字段仅覆盖的属性集合
        Map<String, Set<StorageParamItemAnnoEnum>> fieldOverrideMap = new HashMap<>();

        // 遍历参数类的每个字段
        for (Field field : fields) {
            // 获取字段上的 @StorageParamItem 注解，没有此注解的字段跳过
            StorageParamItem storageParamItemAnnotation = field.getAnnotation(StorageParamItem.class);
            if (storageParamItemAnnotation == null) {
                continue;
            }

            // 如果字段被标记为忽略输入，则只记录字段名，跳过后续处理
            String fieldName = field.getName();
            if (storageParamItemAnnotation.ignoreInput()) {
                useFieldNames.add(fieldName);
                continue;
            }

            // 提取注解中的各项属性值
            String key = storageParamItemAnnotation.key();
            String name = storageParamItemAnnotation.name();
            String description = storageParamItemAnnotation.description();
            boolean required = storageParamItemAnnotation.required();
            // 解析默认值中的占位符（如 ${xxx}），替换为 Spring 配置中的实际值
            String defaultValue = PlaceholderUtils.resolvePlaceholdersBySpringProperties(storageParamItemAnnotation.defaultValue());
            // 解析链接地址
            String link = parseAnnotationLinkField(storageParamItemAnnotation);
            String linkName = storageParamItemAnnotation.linkName();
            StorageParamTypeEnum type = storageParamItemAnnotation.type();
            // 获取选项列表（支持从注解直接获取或从实现类动态获取）
            List<StorageSourceParamDef.Options> optionsList = getOptionsList(storageParamItemAnnotation, storageParam);
            boolean optionAllowCreate = storageParamItemAnnotation.optionAllowCreate();
            int order = storageParamItemAnnotation.order();
            boolean pro = storageParamItemAnnotation.pro();
            String condition = storageParamItemAnnotation.condition();
            boolean hidden = storageParamItemAnnotation.hidden();

            // 若未指定 key，默认使用字段名；若未指定 name，默认使用 key 值
            if (StringUtils.isEmpty(key)) key = fieldName;
            if (StringUtils.isEmpty(name)) name = key;

            // 如果字段已处理过且不是覆盖模式，则跳过（避免重复添加）
            if (useFieldNames.contains(fieldName) && !fieldOverrideMap.containsKey(fieldName)) {
                continue;
            }

            // 获取该字段的覆盖属性集合
            Set<StorageParamItemAnnoEnum> fieldOverrideSet = fieldOverrideMap.get(fieldName);

            // 如果设置了默认值，则该字段不再视为必填
            if (StringUtils.isNotEmpty(defaultValue)) {
                required = false;
            }

            // 获取或创建该字段的参数定义对象
            StorageSourceParamDef storageSourceParamDef = storageSourceParamDefMap.getOrDefault(fieldName, new StorageSourceParamDef());
            // 判断覆盖集合是否为空，为空表示全量设置，不为空表示仅覆盖指定属性
            boolean fieldOverrideSetIsEmpty = CollectionUtils.isEmpty(fieldOverrideSet);
            // 以下每个属性：如果是全量设置或该属性在覆盖列表中，则更新属性值
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.KEY)) {
                storageSourceParamDef.setKey(key);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.NAME)) {
                storageSourceParamDef.setName(name);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.DESCRIPTION)) {
                storageSourceParamDef.setDescription(description);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.REQUIRED)) {
                storageSourceParamDef.setRequired(required);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.DEFAULT_VALUE)) {
                storageSourceParamDef.setDefaultValue(defaultValue);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.LINK)) {
                storageSourceParamDef.setLink(link);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.LINK_NAME)) {
                storageSourceParamDef.setLinkName(linkName);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.TYPE)) {
                storageSourceParamDef.setType(type);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.OPTIONS)) {
                storageSourceParamDef.setOptions(optionsList);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.OPTION_ALLOW_CREATE)) {
                storageSourceParamDef.setOptionAllowCreate(optionAllowCreate);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.ORDER)) {
                storageSourceParamDef.setOrder(order);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.PRO)) {
                storageSourceParamDef.setPro(pro);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.CONDITION)) {
                storageSourceParamDef.setCondition(condition);
            }
            if (fieldOverrideSetIsEmpty || !fieldOverrideSet.contains(StorageParamItemAnnoEnum.HIDDEN)) {
                storageSourceParamDef.setHidden(hidden);
            }
            // 将参数定义存入映射
            storageSourceParamDefMap.put(fieldName, storageSourceParamDef);
            useFieldNames.add(fieldName);

            // 如果注解中指定了 onlyOverwrite 属性，则记录该字段需要覆盖的属性集合
            StorageParamItemAnnoEnum[] storageParamItemAnnoEnumArray = storageParamItemAnnotation.onlyOverwrite();
            if (ArrayUtils.isNotEmpty(storageParamItemAnnoEnumArray)) {
                Set<StorageParamItemAnnoEnum> set = fieldOverrideMap.getOrDefault(fieldName, new HashSet<>());
                set.addAll(Arrays.asList(storageParamItemAnnoEnumArray));
                fieldOverrideMap.put(fieldName, set);
            }
        }

        // 按照 order 属性升序排序
        ArrayList<StorageSourceParamDef> result = new ArrayList<>(storageSourceParamDefMap.values());
        result.sort(Comparator.comparingInt(StorageSourceParamDef::getOrder));

        // 将解析结果写入缓存，下次请求直接命中
        STORAGE_SOURCE_PARAM_CACHE.put(clazz, result);
        return result;
    }

    /**
     * 从注解中获取参数的可选值（options）列表.
     * <p>
     * 获取逻辑分为两个优先级：
     * <ol>
     *   <li>如果注解中指定了 {@code optionsClass}（非默认接口实现），
     *       则通过反射实例化该类，调用其 {@code getOptions()} 方法动态获取选项列表</li>
     *   <li>否则，直接从注解的 {@code options} 属性中读取静态配置的选项</li>
     * </ol>
     * 这种方式既支持静态的选项配置，也支持运行时动态生成选项（如从数据库或API获取）。
     * </p>
     *
     * @param storageParamItemAnnotation 存储源参数注解实例
     * @param storageParam               存储源当前参数值，用于动态选项计算
     * @return 选项列表，如果没有配置任何选项则返回空列表（不会返回 null）
     */
    private static List<StorageSourceParamDef.Options> getOptionsList(StorageParamItem storageParamItemAnnotation, IStorageParam storageParam) {
        // 如果不是默认的空接口实现，优先从实现类中通过反射获取 options 列表（支持动态选项）
        Class<? extends StorageParamSelect> storageParamSelectClass = storageParamItemAnnotation.optionsClass();
        if (BooleanUtils.isNotTrue(storageParamSelectClass.isInterface())) {
            StorageParamSelect storageParamSelect = ReflectUtil.newInstance(storageParamSelectClass);
            List<StorageSourceParamDef.Options> options = storageParamSelect.getOptions(storageParamItemAnnotation, storageParam);
            if (CollectionUtils.isEmpty(options)) {
                return Collections.emptyList();
            }
            return options;
        }

        // 从注解的静态配置中获取 options
        List<StorageSourceParamDef.Options> optionsList = new ArrayList<>();
        StorageParamSelectOption[] options = storageParamItemAnnotation.options();
        if (ArrayUtils.isNotEmpty(options)) {
            for (StorageParamSelectOption storageParamSelectOption : options) {
                StorageSourceParamDef.Options option = new StorageSourceParamDef.Options(storageParamSelectOption);
                optionsList.add(option);
            }
        }
        return optionsList;
    }

    /**
     * 解析注解中的 link 字段，处理相对路径和绝对路径.
     * <p>
     * 如果 link 不为空且不是以 http 或 https 开头，则认为是站内相对路径，
     * 会自动在开头拼接站点的域名（从系统配置中获取），使其成为完整的 URL。
     * 如果 link 已经是完整的 URL（以 http/https 开头），则直接返回。
     * </p>
     *
     * @param storageParamItemAnnotation 存储源参数注解实例
     * @return 解析后的完整链接地址，如果注解中未配置 link 则返回空字符串
     */
    private static String parseAnnotationLinkField(StorageParamItem storageParamItemAnnotation) {
        String link = storageParamItemAnnotation.link();
        // 如果不为空，且不是 http 或 https 开头，则添加站点域名作为前缀
        if (StringUtils.isNotEmpty(link) && !link.toLowerCase().startsWith(StringUtils.HTTP)) {
            SystemConfigService systemConfigService = SpringUtil.getBean(SystemConfigService.class);
            String domain = systemConfigService.getAxiosFromDomainOrSetting();
            link = StringUtils.concat(domain, link);
        }
        return link;
    }

}