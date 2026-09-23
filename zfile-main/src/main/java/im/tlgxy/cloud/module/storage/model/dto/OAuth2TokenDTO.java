package im.tlgxy.cloud.module.storage.model.dto;

import lombok.Data;
import lombok.ToString;

/**
 * OAuth2 令牌数据传输对象（DTO）
 * <p>
 * 封装 OAuth2 授权流程中获取到的令牌信息，用于 OneDrive、Google Drive、
 * SharePoint 等需要 OAuth2 认证的存储源。包含访问令牌（Access Token）、
 * 刷新令牌（Refresh Token）及其关联的客户端信息。
 * </p>
 * <p>
 * 提供静态工厂方法 success() 和 fail() 用于快速创建成功或失败的令牌对象，
 * 避免手动逐字段设置。
 * </p>
 *
 * @author zhaojun
 */
@ToString
@Data
public class OAuth2TokenDTO {

    /** OAuth2 应用的客户端 ID，用于标识应用 */
    private String clientId;

    /** OAuth2 应用的客户端密钥，用于验证应用身份 */
    private String clientSecret;

    /** OAuth2 授权后的回调地址 */
    private String redirectUri;

    /** 访问令牌，用于访问受保护的资源（如调用 API 获取文件列表） */
    private String accessToken;

    /** 刷新令牌，当访问令牌过期时，用于获取新的访问令牌 */
    private String refreshToken;

    /** 本次授权操作是否成功 */
    private boolean success;

    /** 授权接口返回的原始响应体，用于调试和错误分析 */
    private String body;

    /**
     * 令牌到期时间的秒级时间戳
     * 例如：1752994685 表示在 2025-07-20 到期
     */
    private Integer expiredAt;

    /**
     * 创建授权成功的令牌对象
     *
     * @param clientId     OAuth2 客户端 ID
     * @param clientSecret OAuth2 客户端密钥
     * @param redirectUri  回调地址
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param body         原始响应体
     * @param expiredAt    令牌到期时间（秒级时间戳）
     * @return 标记为成功的 OAuth2TokenDTO 对象
     */
    public static OAuth2TokenDTO success(String clientId, String clientSecret, String redirectUri, String accessToken, String refreshToken, String body, Integer expiredAt) {
        OAuth2TokenDTO token = new OAuth2TokenDTO();
        token.setClientId(clientId);
        token.setClientSecret(clientSecret);
        token.setRedirectUri(redirectUri);
        token.setSuccess(true);
        token.setBody(body);
        token.setAccessToken(accessToken);
        token.setRefreshToken(refreshToken);
        token.setExpiredAt(expiredAt);
        return token;
    }

    /**
     * 创建授权失败的令牌对象
     * <p>
     * 失败时没有 accessToken 和 refreshToken，但保留客户端信息和错误响应体以便排查。
     * </p>
     *
     * @param clientId     OAuth2 客户端 ID
     * @param clientSecret OAuth2 客户端密钥
     * @param redirectUri  回调地址
     * @param body         错误响应的原始内容
     * @return 标记为失败的 OAuth2TokenDTO 对象
     */
    public static OAuth2TokenDTO fail(String clientId, String clientSecret, String redirectUri, String body) {
        OAuth2TokenDTO token = new OAuth2TokenDTO();
        token.setClientId(clientId);
        token.setClientSecret(clientSecret);
        token.setRedirectUri(redirectUri);
        token.setSuccess(false);
        token.setBody(body);
        return token;
    }

}