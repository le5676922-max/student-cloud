package im.tlgxy.cloud.module.config.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.convert.ConvertException;
import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.SymmetricAlgorithm;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.util.*;
import im.tlgxy.cloud.module.config.annotation.JSONStringParse;
import im.tlgxy.cloud.module.config.constant.SystemConfigConstant;
import im.tlgxy.cloud.module.config.event.SystemConfigModifyHandlerChain;
import im.tlgxy.cloud.module.config.mapper.SystemConfigMapper;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import im.tlgxy.cloud.module.user.model.enums.LoginVerifyModeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.*;

import static im.tlgxy.cloud.module.config.service.SystemConfigService.CACHE_NAME;


/**
 * 系统配置服务类，负责系统全局配置的读取、更新和缓存管理。
 * <p>
 * 核心功能：
 * <ul>
 *   <li><b>配置读取</b>：通过反射机制将数据库中的 key-value 配置自动映射到 {@link SystemConfigDTO} 对象</li>
 *   <li><b>配置更新</b>：支持批量更新配置，通过责任链模式触发配置变更事件</li>
 *   <li><b>缓存管理</b>：使用 Spring Cache 对配置进行缓存，更新时自动清除缓存</li>
 *   <li><b>AES 密钥管理</b>：自动生成和管理 AES 加密密钥</li>
 *   <li><b>域名解析</b>：提供前端/后端域名的获取逻辑，支持代理和强制地址配置</li>
 *   <li><b>错误页面 URL 生成</b>：生成 401/403/404/500 错误页面的跳转地址</li>
 * </ul>
 *
 * @author zhaojun
 */
@Slf4j
@Service
@CacheConfig(cacheNames = CACHE_NAME)
public class SystemConfigService {

    /**
     * 缓存名称常量
     */
    public static final String CACHE_NAME = "systemConfig";

    /**
     * serialVersionUID 字段名，反射遍历时跳过此字段
     */
    private static final String SERIAL_VERSION_UID_FIELD_NAME = "serialVersionUID";

    /**
     * 系统配置 Mapper，用于数据库操作
     */
    @Resource
    private SystemConfigMapper systemConfigMapper;

    /**
     * Spring 缓存管理器，用于手动管理缓存
     */
    @Resource
    private CacheManager cacheManager;

    /**
     * 系统配置修改责任链，配置更新时通知相关处理器
     */
    @Resource
    private SystemConfigModifyHandlerChain systemConfigModifyHandlerChain;

    /**
     * SystemConfigDTO 的 Class 对象，用于反射操作
     */
    private final Class<SystemConfigDTO> systemConfigClazz = SystemConfigDTO.class;

    /**
     * 加载配置时需要忽略的字段列表
     */
    public static final List<String> ignoreFieldList = Arrays.asList(
            "domain", "guestIndexHtml", "webdavAllowAnonymous", "allowPathLinkAnonAccess");

    /**
     * 获取系统配置 DTO。
     * <p>
     * 优先从缓存中读取，缓存不存在时从数据库加载并自动填充缓存。
     * 使用反射机制将数据库中的 key-value 配置映射到 DTO 的对应字段，
     * 支持枚举类型、JSON 字符串类型和普通类型的自动转换。
     *
     * @return 系统配置 DTO 对象
     */
    @Cacheable(key = "'dto'")
    public SystemConfigDTO getSystemConfig() {
        SystemConfigDTO systemConfigDTO = new SystemConfigDTO();
        // 从数据库获取所有配置项
        List<SystemConfig> systemConfigList = systemConfigMapper.findAll();

        for (SystemConfig systemConfig : systemConfigList) {
            String key = systemConfig.getName();
            // 跳过忽略列表中的字段
            if (ignoreFieldList.contains(key)) {
                if (log.isTraceEnabled()) {
                    log.trace("从数据库加载字段填充到 DTO 时，忽略字段: {}", key);
                }
                continue;
            }
            try {
                // 通过反射获取 DTO 中对应的字段
                Field field = systemConfigClazz.getDeclaredField(key);
                field.setAccessible(true);
                String strVal = systemConfig.getValue();
                Class<?> fieldType = field.getType();

                Object convertVal;
                // 根据字段类型选择合适的转换策略
                if (EnumUtil.isEnum(fieldType)) {
                    // 枚举类型：将字符串转换为对应的枚举值
                    convertVal = EnumConvertUtils.convertStrToEnum(fieldType, strVal);
                } else if (field.isAnnotationPresent(JSONStringParse.class)) {
                    // 带有 @JSONStringParse 注解的字段：按 JSON 字符串解析
                    if (Collection.class.isAssignableFrom(fieldType)) {
                        // 集合类型：将 JSON 数组字符串解析为 List
                        Class<?> genericType = ClassUtils.getGenericType(field);
                        convertVal = JSONArray.parseArray(strVal, genericType);
                    } else {
                        // 普通对象类型：将 JSON 字符串解析为指定对象
                        convertVal = JSONObject.parseObject(strVal, fieldType);
                    }
                } else {
                    // 普通类型：使用 Hutool 的 Convert 工具自动转换
                    convertVal = Convert.convert(fieldType, strVal);
                }
                // 将转换后的值设置到 DTO 中
                field.set(systemConfigDTO, convertVal);
            } catch (NoSuchFieldException | IllegalAccessException | ConvertException e) {
                log.error("通过反射, 将字段 {} 注入 SystemConfigDTO 时出现异常:", key, e);
            }
        }

        return systemConfigDTO;
    }


    /**
     * 更新系统配置并清除缓存。
     * <p>
     * 方法使用事务保证原子性，使用 synchronized 保证并发安全。
     * 更新流程：
     * <ol>
     *   <li>获取数据库中当前的配置值（作为修改前的旧值）</li>
     *   <li>遍历 DTO 的所有字段，对比新旧值，收集有变化的字段</li>
     *   <li>通过责任链通知相关处理器执行附加逻辑（如动态更新 URL 映射）</li>
     *   <li>批量更新数据库中的配置值</li>
     *   <li>清除缓存（通过 @CacheEvict 注解自动实现）</li>
     * </ol>
     *
     * @param systemConfigDTO 包含新配置值的 DTO 对象
     */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(allEntries = true)
    public synchronized void updateSystemConfig(SystemConfigDTO systemConfigDTO) {
        // 获取更新前数据库中所有的配置值
        List<SystemConfig> systemConfigListInDb = systemConfigMapper.findAll();
        Map<String, SystemConfig> systemConfigMapInDb = CollectionUtils.toMap(systemConfigListInDb, null, SystemConfig::getName);

        // 存储有变更的配置项
        List<SystemConfig> updateSystemConfigList = new ArrayList<>();

        // 遍历 DTO 的所有字段，找出有变化的配置项
        Field[] fields = systemConfigClazz.getDeclaredFields();
        for (Field field : fields) {
            String key = field.getName();
            // 跳过 serialVersionUID 字段
            if (SERIAL_VERSION_UID_FIELD_NAME.equals(key)) {
                continue;
            }
            // 获取数据库中的对应配置项
            SystemConfig systemConfig = systemConfigMapInDb.get(key);
            if (systemConfig != null) {
                field.setAccessible(true);
                Object val = null;

                try {
                    // 从 DTO 中获取新值
                    val = field.get(systemConfigDTO);
                } catch (IllegalAccessException e) {
                    log.error("通过反射, 从 SystemConfigDTO 获取字段 {}  时出现异常:", key, e);
                }

                if (val != null) {
                    // 枚举类型：取枚举的 value 值作为存储值
                    if (EnumUtil.isEnum(val)) {
                        val = EnumConvertUtils.convertEnumToStr(val);
                    } else if (field.isAnnotationPresent(JSONStringParse.class)) {
                        // JSON 类型：序列化为 JSON 字符串
                        val = JSONObject.toJSONString(val);
                    }
                    // 如果新值和旧值相同，跳过此字段
                    String originVal = systemConfig.getValue();
                    if (ObjUtil.equals(originVal, val)) {
                        continue;
                    }
                    // 构建待更新的配置实体
                    SystemConfig updateSystemConfig = new SystemConfig();
                    updateSystemConfig.setId(systemConfig.getId());
                    updateSystemConfig.setName(systemConfig.getName());
                    updateSystemConfig.setValue(Convert.toStr(val));
                    updateSystemConfig.setTitle(systemConfig.getTitle());
                    updateSystemConfigList.add(updateSystemConfig);
                }
            } else {
                log.warn("尝试保存系统配置表中不存在字段: {}", key);
            }
        }

        // 依次更新每个有变化的配置项，并触发责任链
        updateSystemConfigList.forEach(systemConfigInForm -> {
            SystemConfig systemConfigInDb = systemConfigMapInDb.get(systemConfigInForm.getName());
            // 通过责任链通知相关处理器（如直链前缀变更、登录入口变更等）
            systemConfigModifyHandlerChain.execute(systemConfigInDb, systemConfigInForm);
            // 更新数据库
            systemConfigMapper.updateById(systemConfigInForm);
        });
    }


    /**
     * 获取或自动生成 AES 加密的十六进制密钥。
     * <p>
     * 如果数据库中已存储密钥则直接返回；如果不存在则自动生成一个新的 AES 密钥，
     * 保存到数据库并更新缓存。该方法使用 synchronized 保证并发安全。
     *
     * @return AES 密钥的十六进制字符串表示
     */
    public synchronized String getAesHexKeyOrGenerate() {
        // 通过 AOP 代理调用以触发缓存
        SystemConfigDTO systemConfigDTO = ((SystemConfigService)AopContext.currentProxy()).getSystemConfig();
        String aesHexKey = systemConfigDTO.getRsaHexKey();
        if (StringUtils.isEmpty(aesHexKey)) {
            // 生成新的 AES 密钥
            byte[] key = SecureUtil.generateKey(SymmetricAlgorithm.AES.getValue()).getEncoded();
            aesHexKey = HexUtil.encodeHexStr(key);

            // 保存到数据库
            SystemConfig loginVerifyModeConfig = systemConfigMapper.findByName(SystemConfigConstant.AES_HEX_KEY);
            loginVerifyModeConfig.setValue(aesHexKey);
            systemConfigMapper.updateById(loginVerifyModeConfig);
            // 更新 DTO 中的值
            systemConfigDTO.setRsaHexKey(aesHexKey);

            // 手动更新缓存
            Cache cache = cacheManager.getCache(CACHE_NAME);
            Optional.ofNullable(cache).ifPresent(cache1 -> cache1.put("dto", systemConfigDTO));
        }
        return aesHexKey;
    }


    /**
     * 获取配置的前端站点域名。
     * <p>
     * 通过 AOP 代理调用以确保缓存生效。
     *
     * @return 前端站点域名，未配置则返回 null
     */
    public String getFrontDomain() {
        SystemConfigDTO systemConfigDTO = ((SystemConfigService)AopContext.currentProxy()).getSystemConfig();
        return systemConfigDTO.getFrontDomain();
    }


    /**
     * 获取实际可用的前端站点域名。
     * <p>
     * 按优先级依次获取：配置的前端域名 > axios-from 参数或强制后端地址 > 请求原始地址。
     *
     * @return 实际可用的前端站点域名
     */
    public String getRealFrontDomain() {
        SystemConfigDTO systemConfigDTO = ((SystemConfigService)AopContext.currentProxy()).getSystemConfig();
        return StringUtils.firstNonNull(systemConfigDTO.getFrontDomain(), getAxiosFromDomainOrSetting(), RequestHolder.getOriginAddress());
    }


    /**
     * 获取后端站点地址，按以下优先级：
     * <ol>
     *   <li>如果设置了强制后端地址，则使用强制后端地址</li>
     *   <li>如果请求中有 axios-from 参数，则使用该参数值</li>
     *   <li>否则使用请求的服务器地址（注意：如果经过反向代理，可能不是实际的后端地址）</li>
     * </ol>
     *
     * @return 后端站点地址
     */
    public String getAxiosFromDomainOrSetting() {
        SystemConfigDTO systemConfigDTO = ((SystemConfigService)AopContext.currentProxy()).getSystemConfig();
        if (StringUtils.isNotBlank(systemConfigDTO.getForceBackendAddress())) {
            // 优先使用配置的强制后端地址
            return systemConfigDTO.getForceBackendAddress();
        } else if (StringUtils.isNotEmpty(RequestHolder.getAxiosFrom())) {
            // 其次使用请求中的 axios-from 参数
            return RequestHolder.getAxiosFrom();
        } else {
            // 最后使用当前请求的服务器地址
            return RequestHolder.getRequestServerAddress();
        }
    }

    /**
     * 获取前端 401（未授权）页面的完整 URL。
     *
     * @return 前端 401 页面地址
     */
    public String getUnauthorizedUrl() {
        return getUnauthorizedUrl(null, null);
    }

    /**
     * 获取前端 401（未授权）页面的完整 URL，可附带错误码和错误信息。
     *
     * @param code    错误码（可选）
     * @param message 错误信息（可选）
     * @return 带查询参数的前端 401 页面地址
     */
    public String getUnauthorizedUrl(String code, String message) {
        String url = StringUtils.concat(getRealFrontDomain(), "/401");
        UrlBuilder urlBuilder = UrlBuilder.of(url);
        if (StringUtils.isNotBlank(code)) {
            urlBuilder.addQuery("code", code);
        }
        if (StringUtils.isNotBlank(message)) {
            urlBuilder.addQuery("message", message);
        }
        return urlBuilder.build();
    }

    /**
     * 获取前端 403（禁止访问）页面的完整 URL。
     *
     * @return 前端 403 页面地址
     */
    public String getForbiddenUrl() {
        return getForbiddenUrl(null, null);
    }

    /**
     * 获取前端 403（禁止访问）页面的完整 URL，可附带错误码和错误信息。
     *
     * @param code    错误码（可选）
     * @param message 错误信息（可选）
     * @return 带查询参数的前端 403 页面地址
     */
    public String getForbiddenUrl(String code, String message) {
        String url = StringUtils.concat(getRealFrontDomain(), "/403");
        UrlBuilder urlBuilder = UrlBuilder.of(url);
        if (StringUtils.isNotBlank(code)) {
            urlBuilder.addQuery("code", code);
        }
        if (StringUtils.isNotBlank(message)) {
            urlBuilder.addQuery("message", message);
        }
        return urlBuilder.build();
    }

    /**
     * 获取前端 404（未找到）页面的完整 URL。
     *
     * @return 前端 404 页面地址
     */
    public String getNotFoundUrl() {
        return getNotFoundUrl(null, null);
    }

    /**
     * 获取前端 404（未找到）页面的完整 URL，可附带错误码和错误信息。
     *
     * @param code    错误码（可选）
     * @param message 错误信息（可选）
     * @return 带查询参数的前端 404 页面地址
     */
    public String getNotFoundUrl(String code, String message) {
        String url = StringUtils.concat(getRealFrontDomain(), "/404");
        UrlBuilder urlBuilder = UrlBuilder.of(url);
        if (StringUtils.isNotBlank(code)) {
            urlBuilder.addQuery("code", code);
        }
        if (StringUtils.isNotBlank(message)) {
            urlBuilder.addQuery("message", message);
        }
        return urlBuilder.build();
    }

    /**
     * 获取前端 500（服务器错误）页面的完整 URL，可附带错误码和错误信息。
     *
     * @param code    错误码（可选）
     * @param message 错误信息（可选）
     * @return 带查询参数的前端 500 页面地址
     */
    public String getErrorPageUrl(String code, String message) {
        String url = StringUtils.concat(getRealFrontDomain(), "/500");
        UrlBuilder urlBuilder = UrlBuilder.of(url);
        if (StringUtils.isNotBlank(code)) {
            urlBuilder.addQuery("code", code);
        }
        if (StringUtils.isNotBlank(message)) {
            urlBuilder.addQuery("message", message);
        }
        return urlBuilder.build();
    }


    /**
     * 重置登录验证模式，关闭所有额外的登录验证方式。
     * <p>
     * 会依次关闭图形验证码、双因素认证，并清空 TOTP Secret，
     * 将登录验证模式重置为关闭状态。
     */
    public void resetLoginVerifyMode() {
        SystemConfigDTO systemConfigDTO = ((SystemConfigService)AopContext.currentProxy()).getSystemConfig();
        systemConfigDTO.setLoginImgVerify(false);
        systemConfigDTO.setAdminTwoFactorVerify(false);
        systemConfigDTO.setLoginVerifySecret("");
        systemConfigDTO.setLoginVerifyMode(LoginVerifyModeEnum.OFF_MODE);
        ((SystemConfigService)AopContext.currentProxy()).updateSystemConfig(systemConfigDTO);
    }

}
