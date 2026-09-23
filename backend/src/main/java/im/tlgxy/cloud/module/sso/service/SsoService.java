package im.tlgxy.cloud.module.sso.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.core.ErrorPageBizException;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.sso.mapper.SsoConfigMapper;
import im.tlgxy.cloud.module.sso.model.entity.SsoConfig;
import im.tlgxy.cloud.module.sso.model.response.SsoLoginItemResponse;
import im.tlgxy.cloud.module.sso.model.response.TokenResponse;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.CopyUserRequest;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static im.tlgxy.cloud.module.sso.service.SsoService.SSO_CONFIG_CACHE_KEY;

/**
 * 单点登录（SSO）核心服务类
 * <p>
 * 负责 OIDC/OAuth2 单点登录的完整流程，包括：
 * <ul>
 *   <li>SSO 配置的增删改查及缓存管理</li>
 *   <li>构造 OAuth2 授权请求 URL 并引导用户跳转</li>
 *   <li>处理 OAuth2 回调——用授权码换取 Access Token，再用 Token 获取用户信息</li>
 *   <li>根据用户信息完成本系统的登录或自动注册</li>
 * </ul>
 * </p>
 *
 * @author OnEvent
 */
@Slf4j
@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = SSO_CONFIG_CACHE_KEY)
public class SsoService {

    /** Spring Cache 中 SSO 配置的缓存名称 */
    public static final String SSO_CONFIG_CACHE_KEY = "ssoConfig";

    /** OAuth2 回调地址模板，{} 将被替换为具体的 provider 名称 */
    private static final String REDIRECT_URI = "/sso/{}/login/callback";

    /** SSO 配置的数据库访问层 */
    private final SsoConfigMapper ssoConfigMapper;

    /** 系统配置服务，用于获取域名等信息 */
    private final SystemConfigService systemConfigService;

    /** 用户服务，用于用户查询和自动注册 */
    private final UserService userService;

    /**
     * 查询所有 SSO 配置
     *
     * @return 所有单点登录配置列表
     */
    public List<SsoConfig> list() {
        return ssoConfigMapper.findAll();
    }

    /**
     * 查询所有已启用的 SSO 登录项（仅含展示字段）
     * <p>
     * 用于前端登录页展示可用的 SSO 登录方式，不返回敏感信息。
     * </p>
     *
     * @return 登录页展示用的 SSO 登录项列表
     */
    public List<SsoLoginItemResponse> listAllLoginItems() {
        return ssoConfigMapper.findAllLoginItems();
    }

    /**
     * 根据厂商名获取 SSO 配置（带缓存）
     * <p>
     * 使用 Spring Cache 缓存结果，key 为 provider。
     * 当 provider 不为 null 且结果不为 null 时才缓存。
     * 通过 AOP 代理调用本方法以触发缓存注解。
     * </p>
     *
     * @param provider OIDC/OAuth2 厂商名
     * @return 对应的 SSO 配置，未找到时返回 null
     */
    @Cacheable(key = "#provider", unless = "#result == null", condition = "#provider != null")
    public SsoConfig getProvider(String provider) {
        return ssoConfigMapper.findByProvider(provider);
    }

    /**
     * 保存或更新 SSO 配置
     * <p>
     * 先检查厂商名是否重复，再根据 ID 是否为 null 来判断是新增还是更新。
     * 操作完成后清除对应 provider 的缓存。
     * </p>
     *
     * @param ssoConfig 要保存的 SSO 配置实体
     * @return 保存后的 SSO 配置实体
     * @throws BizException 当厂商名重复时抛出
     */
    @CacheEvict(key = "#result.provider")
    public SsoConfig saveOrUpdate(SsoConfig ssoConfig) {
        // 检查厂商名是否重复（排除自身 ID）
        boolean providerIsDuplicate = checkDuplicateProvider(ssoConfig.getId(), ssoConfig.getProvider());
        if (providerIsDuplicate) {
            throw new BizException(ErrorCode.BIZ_SSO_PROVIDER_EXIST);
        }

        // ID 为 null 表示新增，否则为更新
        if (ssoConfig.getId() == null) {
            ssoConfigMapper.insert(ssoConfig);
        } else {
            ssoConfigMapper.updateById(ssoConfig);
        }
        return ssoConfig;
    }

    /**
     * 删除指定厂商的 SSO 配置
     * <p>
     * 根据 provider 删除配置，并清除对应的缓存。
     * </p>
     *
     * @param provider OIDC/OAuth2 厂商名
     */
    @CacheEvict(key = "#provider")
    public void deleteProvider(String provider) {
        ssoConfigMapper.deleteById(provider);
    }

    /**
     * 检查厂商名是否重复
     * <p>
     * 查询数据库中是否存在相同 provider 的其他记录（排除 ignoreId 指定的记录）。
     * </p>
     *
     * @param ignoreId 需要忽略的记录 ID（新增时为 null）
     * @param provider 要检查的厂商名
     * @return true 表示存在重复，false 表示不重复
     */
    public boolean checkDuplicateProvider(Integer ignoreId, String provider) {
        return ssoConfigMapper.countByProvider(provider, ignoreId) > 0;
    }

    /**
     * 获取 OIDC/OAuth2 的授权地址并引导用户重定向
     * <p>
     * 根据配置构造完整的 OAuth2 授权请求 URL（包含 response_type、client_id、
     * redirect_uri、state、scope 等参数），由后端控制页面重定向到 SSO 厂商的授权页面。
     * 通过 AOP 代理获取 provider 配置以触发缓存。
     * </p>
     *
     * @param provider OIDC/OAuth2 厂商名
     * @param state    状态值，用于防止 CSRF 攻击，回调时会原样返回
     * @return 完整的 SSO 授权请求 URL
     * @throws ErrorPageBizException 当厂商不存在或已禁用时抛出
     */
    public String getAuthRedirectUrl(String provider, String state) {
        // 通过 AOP 代理调用以触发缓存注解
        SsoConfig config = ((SsoService) AopContext.currentProxy()).getProvider(provider);
        if (ObjectUtil.isNull(config)) {
            throw new ErrorPageBizException("供应商: [" + provider + "] 不存在, 请检查配置");
        }

        // 检查该 SSO 配置是否已启用
        if (BooleanUtil.isFalse(config.getEnabled())) {
            throw new ErrorPageBizException(ErrorCode.BIZ_SSO_PROVIDER_DISABLED);
        }

        // 构造 OAuth2 授权请求参数
        Map<String, String> authParamsMap = new HashMap<>() {{
            put("response_type", "code");                                                                          // 使用授权码模式
            put("client_id", config.getClientId());                                                                // 客户端 ID
            put("redirect_uri", systemConfigService.getAxiosFromDomainOrSetting() + StrUtil.format(REDIRECT_URI, provider)); // 回调地址
            put("state", state);                                                                                   // 防 CSRF 状态值
            put("scope", config.getScope());                                                                       // 授权范围
        }};
        String authParamsStr = HttpUtil.toParams(authParamsMap);
        return config.getAuthUrl() + "?" + authParamsStr;
    }

    /**
     * 处理 OIDC/OAuth2 回调——完整的登录流程
     * <p>
     * 处理流程：
     * <ol>
     *   <li>获取 SSO 配置并校验启用状态</li>
     *   <li>用授权码（code）向 Token 端点换取 Access Token</li>
     *   <li>用 Access Token 向用户信息端点请求用户信息</li>
     *   <li>根据 bindingField 表达式从用户信息 JSON 中提取绑定字段值</li>
     *   <li>查找本地用户：存在则直接登录，不存在则根据模板用户自动注册后再登录</li>
     *   <li>返回重定向 URL，携带登录 Token</li>
     * </ol>
     * </p>
     *
     * @param provider OIDC/OAuth2 厂商名
     * @param code     SSO 厂商回调时附带的授权码
     * @return 登录成功后前端重定向的 URL（可能包含 token 参数）
     * @throws ErrorPageBizException 当 SSO 配置已禁用、Token 获取失败、Token 类型错误、
     *                                用户信息获取失败、绑定字段解析失败或新用户注册未启用时抛出
     */
    public String callbackHandler(String provider, String code) {
        // 通过 AOP 代理获取配置，以触发缓存
        SsoConfig config = ((SsoService) AopContext.currentProxy()).getProvider(provider);
        if (log.isDebugEnabled()) {
            log.debug("[Callback] 单点登录厂商 {} 配置信息: {}", provider, config);
        }

        // 校验该 SSO 配置是否已启用
        if (BooleanUtil.isFalse(config.getEnabled())) {
            throw new ErrorPageBizException(ErrorCode.BIZ_SSO_PROVIDER_DISABLED);
        }

        // ========== 第一步：用授权码换取 Access Token ==========
        Map<String, String> tokenParamsMap = new HashMap<>() {{
            put("code", code);                                                                                    // 授权码
            put("client_id", config.getClientId());                                                               // 客户端 ID
            put("client_secret", config.getClientSecret());                                                       // 客户端密钥
            put("redirect_uri", systemConfigService.getAxiosFromDomainOrSetting() + StrUtil.format(REDIRECT_URI, provider)); // 回调地址
            put("grant_type", "authorization_code");                                                              // 授权类型为授权码模式
        }};

        // 向 SSO 厂商的 Token 端点发送 POST 请求
        HttpResponse getTokenResponse = HttpUtil.createPost(config.getTokenUrl())
                .header(Header.ACCEPT, "application/json")
                .body(HttpUtil.toParams(tokenParamsMap))
                .execute();
        String tokenStr = getTokenResponse.body();
        if (log.isDebugEnabled()) {
            log.debug("[Token] 单点登录厂商返回的 Token 信息: {}", tokenStr);
        }
        // 检查 Token 请求是否成功
        if (!getTokenResponse.isOk()) {
            log.error("单点登录厂商 {} 返回错误: {}, 错误信息: {}", provider, getTokenResponse.getStatus(), tokenStr);
            throw new ErrorPageBizException("单点登录失败: " + getTokenResponse.getStatus() + ", " + tokenStr);
        }

        // 反序列化 Token 响应，使用智能匹配支持驼峰和下划线命名
        TokenResponse token = JSON.parseObject(tokenStr, TokenResponse.class, JSONReader.Feature.SupportSmartMatch);
        // 校验 Token 类型必须为 Bearer
        if (!"bearer".equalsIgnoreCase(token.getTokenType())) {
            throw new ErrorPageBizException("Access Token 类型错误, 需要 Bearer 类型, 请检查配置");
        }


        // ========== 第二步：用 Access Token 获取用户信息 ==========
        HttpResponse userInfoResponse = HttpUtil
                .createGet(config.getUserInfoUrl())
                .bearerAuth(token.getAccessToken())       // 在请求头中添加 Bearer Token 认证
                .execute();
        String userInfoStr = userInfoResponse.body();
        if (log.isDebugEnabled()) {
            log.debug("[UserInfo] 单点登录服务商处请求 {} 的用户信息: {}，将尝试通过 {} 表达式获取字段", config.getUserInfoUrl(), userInfoStr, config.getBindingField());
        }
        // 检查用户信息请求是否成功
        if (!userInfoResponse.isOk()) {
            log.error("单点登录服务商 {} 返回错误: {}, 错误信息: {}", provider, userInfoResponse.getStatus(), userInfoStr);
            throw new ErrorPageBizException("从单点登录服务商获取用户信息失败: " + userInfoResponse.getStatus() + ", " + userInfoStr);
        }

        // 根据 bindingField 表达式从用户信息 JSON 中提取绑定字段值（支持 JSON Path）
        Object bindingField = JSON.parseObject(userInfoStr, JSONReader.Feature.SupportSmartMatch).getByPath(config.getBindingField());
        if (log.isDebugEnabled()) {
            log.debug("[UserInfo] 通过表达式 [{}] 获取到字段: {}", config.getBindingField(), bindingField);
        }

        // 校验绑定字段值不能为空
        if (StrUtil.isBlankIfStr(bindingField)) {
            throw new ErrorPageBizException("解析用户信息失败, 请检查配置");
        }


        // ========== 第三步：根据绑定字段值查找或创建本地用户 ==========
        String bindingFieldStr = Convert.toStr(bindingField);
        User user = userService.getByUsername(bindingFieldStr);
        if (user == null) {
            // 用户不存在，尝试自动注册
            // 获取新用户模板，用于复制默认权限和配置
            User templateUser = userService.getById(UserConstant.NEW_USER_TEMPLATE_ID);
            if (!BooleanUtil.isTrue(templateUser.getEnable())) {
                // 系统未启用新用户注册功能
                throw new ErrorPageBizException("当前系统未启用新用户注册, 请联系管理员");
            }

            // 从模板用户复制创建新用户
            Integer newUserId = userService.registerFromTemplate(bindingFieldStr);

            log.info("新用户 {} 通过单点登录注册成功, ID: {}", bindingFieldStr, newUserId);

            // 构造用户对象用于后续登录
            user = new User();
            user.setId(newUserId);
        }

        // ========== 第四步：执行登录并构造重定向 URL ==========
        // 使用 Sa-Token 执行登录
        userService.requireLoginUser(user.getId());
        StpUtil.login(user.getId());
        String axiosFromDomainOrSetting = systemConfigService.getAxiosFromDomainOrSetting();
        String frontDomain = systemConfigService.getFrontDomain();

        String redirectUrl;
        if (StringUtils.isBlank(frontDomain)) {
            // 未配置前端独立域名，使用同域跳转
            redirectUrl = axiosFromDomainOrSetting;
        } else {
            // 如果配置了前端域名，则跳转到前端域名并携带 Token
            redirectUrl = frontDomain + "/sso?token=" + StpUtil.getTokenValue();
        }

        if (log.isDebugEnabled()) {
            log.debug("用户 {} 通过单点登录登录成功, ID: {}，将跳转到 {}, token 为 {}", bindingFieldStr, user.getId(), redirectUrl, StpUtil.getTokenValue());
        }
        return redirectUrl;
    }

}
