package im.tlgxy.cloud.module.sso.model.response;

import lombok.Data;

/**
 * OAuth2 / OIDC Token 响应体
 * <p>
 * 用于反序列化 SSO 厂商 Token 端点返回的 JSON 响应。
 * 包含 OAuth2 标准中定义的 access_token、id_token、refresh_token 等字段。
 * 反序列化时使用 SupportSmartMatch 特性，支持驼峰和下划线的智能匹配。
 * </p>
 */
@Data
public class TokenResponse {

    /**
     * ID Token（JWT 格式）
     * OIDC 协议中用于携带用户身份信息的令牌，包含用户的基本信息声明（claims）
     */
    private String idToken;

    /**
     * 访问令牌（Access Token）
     * OAuth2 中用于访问受保护资源（如用户信息端点）的凭证
     */
    private String accessToken;

    /**
     * 刷新令牌（Refresh Token）
     * 用于在 Access Token 过期后获取新的 Access Token，有效期通常较长
     */
    private String refreshToken;

    /**
     * 授权范围
     * 表示该 Token 被授予的权限范围，与请求时的 scope 参数相对应
     */
    private String scope;

    /**
     * Token 类型
     * 通常为 "Bearer"，表示使用 Bearer Token 方式进行身份认证
     */
    private String tokenType;

    /**
     * Access Token 的有效期（秒）
     * 表示 Access Token 从颁发时刻起可以使用的时长
     */
    private Long expiresIn;

    /**
     * Refresh Token 的有效期
     * 表示 Refresh Token 从颁发时刻起可以使用的时长（格式因厂商而异，可能是秒数或时间戳）
     */
    private String refreshTokenExpiresIn;

}
