package im.tlgxy.cloud.module.storage.oauth2.service;

import cn.hutool.core.codec.Base64;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.dto.OAuth2TokenDTO;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Google Drive OAuth2 认证服务实现类
 * <p>
 * 实现 Google Drive 的 OAuth2 认证流程。
 * 使用 Google Identity Services 的 OAuth2 v2 授权码流程，
 * 支持通过授权码换取访问令牌和刷新令牌。
 * </p>
 * <p>
 * 特点：
 * - 强制使用 access_type=offline 以获取刷新令牌
 * - 强制使用 prompt=consent 确保每次都能获取到刷新令牌
 * - 使用自定义的 NoRedirectClientHttpRequestFactory 处理重定向
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
@Component
public class GoogleDriveOAuth2ServiceImpl implements IOAuth2Service {

    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * Google OAuth2 令牌端点 URL
     */
    public static final String GOOGLE_OAUTH2_URL = "https://accounts.google.com/o/oauth2/token";

    /**
     * 生成 Google OAuth2 授权 URL
     * <p>
     * 构建 Google 的 OAuth2 v2 授权页面 URL。
     * 如果传入的 clientId、clientSecret、redirectUri 全部为空，
     * 则使用配置文件中 Google Drive 的默认值。
     * 强制设置 access_type=offline 和 prompt=consent 以获取刷新令牌。
     * </p>
     *
     * @param clientId     客户端 ID，为空时使用配置文件中的默认值
     * @param clientSecret 客户端密钥，为空时使用配置文件中的默认值
     * @param redirectUri  授权回调地址，为空时使用配置文件中的默认值
     * @return Google OAuth2 授权页面 URL
     */
    @Override
    public String generateAuthorizationUrl(String clientId, String clientSecret, String redirectUri) {
        // 如果参数全部为空，则使用配置文件中的默认值
        if (StringUtils.isAllEmpty(clientId, clientSecret, redirectUri)) {
            clientId = tlgxyCloudProperties.getGd().getClientId();
            clientSecret = tlgxyCloudProperties.getGd().getClientSecret();
            redirectUri = tlgxyCloudProperties.getGd().getRedirectUri();
        }

        // 将凭据信息编码到 state 参数中
        String stateStr = "&state=" + Base64.encodeUrlSafe(StringUtils.join("::", clientId, clientSecret, redirectUri));

        // 构建 Google OAuth2 授权 URL，强制 offline 模式和 consent 提示
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + clientId
                + "&response_type=code&redirect_uri=" + redirectUri
                + "&scope=" + tlgxyCloudProperties.getGd().getScope()
                + "&access_type=offline"
                + "&prompt=consent"
                + stateStr;
    }

    /**
     * 通过授权码（code）换取 Google OAuth2 访问令牌
     * <p>
     * 使用 OAuth2 授权码流程，向 Google 的令牌端点发送 POST 请求，
     * 用授权码换取访问令牌和刷新令牌。
     * 使用 Basic 认证方式（Base64 编码的 clientId:clientSecret）
     * 进行客户端身份验证。
     * </p>
     *
     * @param code         授权回调返回的授权码
     * @param clientId     客户端 ID，为空时使用配置文件中的默认值
     * @param clientSecret 客户端密钥，为空时使用配置文件中的默认值
     * @param redirectUri  授权回调地址，为空时使用配置文件中的默认值
     * @return OAuth2TokenDTO 包含访问令牌、刷新令牌等信息的 DTO 对象；
     *         如果 HTTP 状态码不是 200，则返回失败结果的 DTO
     */
    @Override
    public OAuth2TokenDTO getTokenByCode(String code, String clientId, String clientSecret, String redirectUri) {
        // 如果参数全部为空，则使用配置文件中的默认值
        if (StringUtils.isAllEmpty(clientId, clientSecret, redirectUri)) {
            clientId = tlgxyCloudProperties.getGd().getClientId();
            clientSecret = tlgxyCloudProperties.getGd().getClientSecret();
            redirectUri = tlgxyCloudProperties.getGd().getRedirectUri();
        }

        // 构建 HTTP 请求头，使用 Basic 认证
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String clientCredentials = Base64.encodeUrlSafe(clientId + ":" + clientSecret);
        headers.add("Authorization", "Basic " + clientCredentials);

        // 构建请求体参数
        MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("code", code);
        requestBody.add("grant_type", "authorization_code");
        requestBody.add("redirect_uri", redirectUri);
        requestBody.add("scope", tlgxyCloudProperties.getGd().getScope());

        HttpEntity<MultiValueMap<String, String>> formEntity = new HttpEntity<>(requestBody, headers);

        // 使用自定义的请求工厂（禁止自动重定向）发送 POST 请求
        ResponseEntity<String> response = new RestTemplate(new NoRedirectClientHttpRequestFactory())
                                                .exchange(GOOGLE_OAUTH2_URL, HttpMethod.POST, formEntity, String.class);

        String responseBody = response.getBody();
        // HTTP 状态码不是 200 时返回失败结果
        if (response.getStatusCode() != HttpStatus.OK) {
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
     * 自定义 HTTP 请求工厂，禁止自动重定向
     * <p>
     * 继承自 SimpleClientHttpRequestFactory，
     * 覆盖 prepareConnection 方法，设置连接允许跟随重定向。
     * 用于 Google OAuth2 令牌请求场景，防止自动跳转导致无法获取响应内容。
     * </p>
     */
    private static class NoRedirectClientHttpRequestFactory extends
            SimpleClientHttpRequestFactory {

        /**
         * 准备 HTTP 连接，设置允许跟随重定向
         *
         * @param connection HTTP URL 连接对象
         * @param httpMethod HTTP 请求方法（GET、POST 等）
         * @throws IOException 如果连接准备失败
         */
        @Override
        protected void prepareConnection(@NotNull HttpURLConnection connection,
                                         @NotNull String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(true);
        }
    }

}
