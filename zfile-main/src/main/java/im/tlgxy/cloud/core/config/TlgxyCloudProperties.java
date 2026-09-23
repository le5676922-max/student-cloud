package im.tlgxy.cloud.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TlgxyCloud 配置属性类，用于将 Spring Boot 配置文件（如 application.yml）中以
 * "tongligong-student-cloud" 为前缀的配置项
 * 自动映射到该类的属性上。
 * <p>
 * 通过 Spring Boot 的 {@link ConfigurationProperties} 机制，实现类型安全的配置绑定。
 * 支持 debug 模式、版本号、演示站点模式以及各存储源的 OAuth2 认证配置。
 * </p>
 * <p>
 * 配置示例：<br>
 * <pre>{@code
 * tongligong-student-cloud:
 *   debug: false
 *   version: 1.0.0
 *   is-demo-site: false
 *   onedrive:
 *     client-id: xxx
 *     client-secret: xxx
 *     redirect-uri: xxx
 *     scope: xxx
 * }</pre>
 * </p>
 *
 * @author zhaojun
 */
@Data
// 启用 @ConfigurationProperties 注解的类自动注册
@EnableConfigurationProperties
// 注册为 Spring 组件
@Component
// 绑定配置文件中以 "tongligong-student-cloud" 为前缀的属性
@ConfigurationProperties(prefix = "tongligong-student-cloud")
public class TlgxyCloudProperties {

    /**
     * 是否为 debug 模式，开启后会输出更多调试日志
     */
    private boolean debug;

    /**
     * TlgxyCloud 当前版本号
     */
    private String version;

    /**
     * 是否为演示站点，演示站点会禁用部分敏感操作（如删除、修改配置等）
     */
    private boolean isDemoSite;

    /**
     * OneDrive 国际版的 OAuth2 认证配置
     */
    private OAuth2Properties onedrive = new OAuth2Properties();

    /**
     * OneDrive 中国版（世纪互联版）的 OAuth2 认证配置
     */
    private OAuth2Properties onedriveChina = new OAuth2Properties();

    /**
     * Google Drive 的 OAuth2 认证配置
     */
    private OAuth2Properties gd = new OAuth2Properties();

    /**
     * 115 网盘的 OpenAPI 认证配置
     */
    private Open115Properties open115 = new Open115Properties();

    /**
     * OAuth2 认证通用配置内部类。
     * 包含 OAuth2 协议所需的客户端 ID、密钥、回调地址和授权范围等。
     */
    @Data
    public static class OAuth2Properties {
        /**
         * OAuth2 客户端 ID
         */
        private String clientId;
        /**
         * OAuth2 客户端密钥
         */
        private String clientSecret;
        /**
         * OAuth2 授权回调地址
         */
        private String redirectUri;
        /**
         * OAuth2 授权范围
         */
        private String scope;
    }

    /**
     * 115 网盘 OpenAPI 配置内部类
     */
    @Data
    public static class Open115Properties {
        /**
         * 115 网盘的应用 ID
         */
        private String appId;
    }

}
