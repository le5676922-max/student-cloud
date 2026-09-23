package im.tlgxy.cloud.module.storage.oauth2.service;

import im.tlgxy.cloud.module.storage.model.dto.OAuth2TokenDTO;

/**
 * OAuth2 认证服务接口
 * <p>
 * 定义了 OAuth2 认证流程的核心方法。
 * 所有需要 OAuth2 认证的云存储服务（如 OneDrive、Google Drive 等）
 * 都需要实现此接口，提供授权 URL 生成和通过授权码换取令牌的功能。
 * </p>
 * <p>
 * 接口中定义了三个常量字段名，用于从 OAuth2 提供商的响应 JSON 中
 * 提取访问令牌、刷新令牌和过期时间。
 * </p>
 *
 * @author zhaojun
 */
public interface IOAuth2Service {

    /**
     * 访问令牌字段名称，对应 JSON 响应中的 access_token 字段
     */
    String ACCESS_TOKEN_FIELD_NAME = "access_token";

    /**
     * 刷新令牌字段名称，对应 JSON 响应中的 refresh_token 字段
     */
    String REFRESH_TOKEN_FIELD_NAME = "refresh_token";

    /**
     * 过期时间字段名称，对应 JSON 响应中的 expires_in 字段（单位为秒）
     */
    String EXPIRES_IN_FIELD_NAME = "expires_in";

    /**
     * 生成 OAuth2 授权 URL
     * <p>
     * 构建 OAuth2 提供商的授权页面 URL，用户访问该 URL 后
     * 会跳转到授权页面进行登录和授权操作。
     * </p>
     *
     * @param clientId     客户端 ID
     * @param clientSecret 客户端密钥
     * @param redirectUri  授权回调地址
     * @return OAuth2 授权页面 URL
     */
    String generateAuthorizationUrl(String clientId, String clientSecret, String redirectUri);

    /**
     * 通过授权码（code）换取访问令牌
     * <p>
     * 用户授权后，OAuth2 提供商会回调 redirectUri 并附带授权码，
     * 使用此授权码向提供商换取访问令牌和刷新令牌。
     * </p>
     *
     * @param code         授权回调返回的授权码
     * @param clientId     客户端 ID
     * @param clientSecret 客户端密钥
     * @param redirectUri  授权回调地址
     * @return OAuth2TokenDTO 包含访问令牌、刷新令牌等信息的 DTO 对象
     */
    OAuth2TokenDTO getTokenByCode(String code, String clientId, String clientSecret, String redirectUri);

}
