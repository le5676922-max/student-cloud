package im.tlgxy.cloud.module.config.constant;

/**
 * 系统配置字段常量类，定义了系统设置中使用的所有配置键名。
 * <p>
 * 这些常量用于在系统配置表中标识不同的配置项，避免硬编码字符串。
 *
 * @author zhaojun
 */
public class SystemConfigConstant {

    /**
     * 系统登录用户名
     */
    public static final String USERNAME = "username";

    /**
     * 系统登录密码
     */
    public static final String PASSWORD = "password";

    /**
     * 登录验证方式（如：图片验证码、双因素认证等）
     */
    public static final String LOGIN_VERIFY_MODE = "loginVerifyMode";

    /**
     * AES 加密的十六进制密钥。
     * 注意：常量名称为 AES_HEX_KEY，但实际存储的键名为 rsaHexKey，
     * 这是历史遗留问题——最初设计时命名混淆了 RSA 和 AES，实际使用的是 AES 加密。
     */
    public static final String AES_HEX_KEY = "rsaHexKey";

}