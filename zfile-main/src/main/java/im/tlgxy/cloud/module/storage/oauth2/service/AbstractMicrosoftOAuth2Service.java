package im.tlgxy.cloud.module.storage.oauth2.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.dto.OAuth2TokenDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * Microsoft OAuth2 认证服务抽象类
 * <p>
 * 为 Microsoft 体系（包括国际版 OneDrive 和世纪互联版 OneDrive China）
 * 提供 OAuth2 认证的通用实现。使用 Microsoft Identity Platform (v2.0) 的
 * OAuth2 授权码流程进行身份验证和令牌获取。
 * </p>
 * <p>
 * 子类需要实现以下抽象方法以提供各自的服务端点、客户端凭据和权限范围：
 * {@link #getEndPoint()}、{@link #getClientId()}、{@link #getClientSecret()}、
 * {@link #getRedirectUri()}、{@link #getScope()}。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public abstract class AbstractMicrosoftOAuth2Service implements IOAuth2Service {

    /**
     * Microsoft OAuth2 令牌端点 URI 模板
     * {authenticateEndPoint} 会被替换为实际的认证端点地址
     */
    private static final String AUTHENTICATE_URL = "https://{authenticateEndPoint}/common/oauth2/v2.0/token";

    /**
     * 生成 Microsoft OAuth2 授权 URL
     * <p>
     * 构建 Microsoft Identity Platform 的 OAuth2 v2.0 授权页面 URL。
     * 如果传入的 clientId、clientSecret、redirectUri 全部为空，
     * 则使用子类中配置的默认值。
     * 同时将客户端凭据和回调地址编码到 state 参数中，以便回调时恢复。
     * </p>
     *
     * @param clientId     客户端 ID，为空时使用子类配置的默认值
     * @param clientSecret 客户端密钥，为空时使用子类配置的默认值
     * @param redirectUri  授权回调地址，为空时使用子类配置的默认值
     * @return Microsoft OAuth2 授权页面 URL
     */
    @Override
    public String generateAuthorizationUrl(String clientId, String clientSecret, String redirectUri) {
        // 如果参数全部为空，则使用子类中配置的默认值
        if (StringUtils.isAllEmpty(clientId, clientSecret, redirectUri)) {
            clientId = getClientId();
            clientSecret = getClientSecret();
            redirectUri = getRedirectUri();
        }

        // 将 clientId、clientSecret、redirectUri 编码到 state 参数中
        String stateStr = "&state=" + Base64.encodeUrlSafe(StringUtils.join("::", clientId, clientSecret, redirectUri));

        // 构建完整的授权 URL
        return "https://" + getEndPoint() + "/common/oauth2/v2.0/authorize?client_id=" + clientId
                + "&response_type=code&redirect_uri=" + redirectUri
                + "&scope=" + getScope()
                + stateStr;
    }

    /**
     * 通过授权码（code）换取 Microsoft OAuth2 访问令牌
     * <p>
     * 使用 OAuth2 授权码流程，向 Microsoft Identity Platform 的令牌端点
     * 发送 POST 请求，用授权码换取访问令牌和刷新令牌。
     * </p>
     *
     * @param code         授权回调返回的授权码
     * @param clientId     客户端 ID，为空时使用子类配置的默认值
     * @param clientSecret 客户端密钥，为空时使用子类配置的默认值
     * @param redirectUri  授权回调地址，为空时使用子类配置的默认值
     * @return OAuth2TokenDTO 包含访问令牌、刷新令牌等信息的 DTO 对象；
     *         如果 HTTP 状态码不是 200，则返回失败结果的 DTO
     */
    @Override
    public OAuth2TokenDTO getTokenByCode(String code, String clientId, String clientSecret, String redirectUri) {
        // 如果参数全部为空，则使用子类中配置的默认值
        if (StringUtils.isAllEmpty(clientId, clientSecret, redirectUri)) {
            clientId = getClientId();
            clientSecret = getClientSecret();
            redirectUri = getRedirectUri();
        }

        // 构建请求参数（application/x-www-form-urlencoded 格式）
        String param = "client_id=" + clientId +
                "&redirect_uri=" + redirectUri +
                "&client_secret=" + clientSecret +
                "&code=" + code +
                "&scope=" + getScope() +
                "&grant_type=authorization_code";


        if (log.isDebugEnabled()) {
            log.debug("根据授权回调 code 获取令牌, 请求参数: [{}]", param);
        }

        // 替换认证端点并发送 POST 请求
        String authenticateUrl = AUTHENTICATE_URL.replace("{authenticateEndPoint}", getEndPoint());
        HttpResponse response = HttpUtil.createPost(authenticateUrl)
                .body(param, "application/x-www-form-urlencoded")
                .execute();

        String responseBody = response.body();
        int responseStatus = response.getStatus();

        // HTTP 状态码不是 200 时返回失败结果
        if (responseStatus != HttpStatus.OK.value()) {
            return OAuth2TokenDTO.fail(clientId, clientSecret, redirectUri, responseBody);
        }

        // 解析 JSON 响应，提取令牌信息
        JSONObject jsonBody = JSONObject.parseObject(responseBody);
        String accessToken = jsonBody.getString(ACCESS_TOKEN_FIELD_NAME);
        String refreshToken = jsonBody.getString(REFRESH_TOKEN_FIELD_NAME);
        Integer expiresIn = jsonBody.getInteger(EXPIRES_IN_FIELD_NAME);
        return OAuth2TokenDTO.success(clientId, clientSecret, redirectUri, accessToken, refreshToken, responseBody, expiresIn);
    }

    /**
     * 获取 Microsoft 认证服务的端点地址
     * <p>
     * 国际版返回 "login.microsoftonline.com"，
     * 世纪互联版返回 "login.partner.microsoftonline.cn"。
     * </p>
     *
     * @return 认证端点地址
     */
    public abstract String getEndPoint();

    /**
     * 获取 OAuth2 客户端 ID（Application ID）
     *
     * @return 客户端 ID
     */
    public abstract String getClientId();

    /**
     * 获取 OAuth2 客户端密钥（Client Secret）
     *
     * @return 客户端密钥
     */
    public abstract String getClientSecret();

    /**
     * 获取 OAuth2 授权回调地址（Redirect URI）
     *
     * @return 回调地址
     */
    public abstract String getRedirectUri();

    /**
     * 获取 OAuth2 权限范围（Scope）
     *
     * @return 权限范围字符串
     */
    public abstract String getScope();

}
