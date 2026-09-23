package im.tlgxy.cloud.module.storage.constant;

/**
 * 存储源配置字段名称常量类。
 *
 * 定义了存储源配置项中使用到的各类字段名称，这些字段名称在数据库的存储源配置表中
 * 作为配置项的 key 使用。通过统一的常量定义，避免在代码中硬编码字符串，降低出错风险。
 *
 * 各常量说明：
 * - ACCESS_TOKEN_KEY：OAuth2 认证的访问令牌
 * - REFRESH_TOKEN_KEY：OAuth2 认证的刷新令牌，用于在 accessToken 过期后自动续期
 * - REFRESH_TOKEN_EXPIRED_AT_KEY：刷新令牌的过期时间
 * - PROXY_DOWNLOAD_KEY：是否启用代理下载功能的开关
 * - PROXY_UPLOAD_KEY：是否启用代理上传功能的开关
 *
 * @author zhaojun
 */
public class StorageConfigConstant {

    /**
     * 访问令牌（Access Token）的配置键名。
     * 用于 OAuth2 认证流程中存储和读取访问令牌。
     */
    public static final String ACCESS_TOKEN_KEY = "accessToken";

    /**
     * 刷新令牌（Refresh Token）的配置键名。
     * 用于 OAuth2 认证流程中存储和读取刷新令牌，以便在 accessToken 过期后自动获取新的令牌。
     */
    public static final String REFRESH_TOKEN_KEY = "refreshToken";

    /**
     * 刷新令牌过期时间的配置键名。
     * 存储刷新令牌的过期时间戳，用于判断是否需要提前刷新令牌。
     */
    public static final String REFRESH_TOKEN_EXPIRED_AT_KEY = "refreshTokenExpiredAt";

    /**
     * 是否启用代理下载的配置键名。
     * 当启用时，文件下载流量会经过 TlgxyCloud 服务器中转，而不是直接重定向到存储源。
     */
    public static final String PROXY_DOWNLOAD_KEY = "enableProxyDownload";

    /**
     * 是否启用代理上传的配置键名。
     * 当启用时，文件上传流量会经过 TlgxyCloud 服务器中转，而不是直接上传到存储源。
     */
    public static final String PROXY_UPLOAD_KEY = "enableProxyUpload";

}
