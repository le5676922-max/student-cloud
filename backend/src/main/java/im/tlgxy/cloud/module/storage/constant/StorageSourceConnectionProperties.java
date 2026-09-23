package im.tlgxy.cloud.module.storage.constant;

/**
 * 存储源连接超时配置常量类。
 *
 * 定义了与存储源建立连接时的默认超时时间。这些值作为系统级默认配置使用，
 * 当用户未单独指定连接超时时间时，系统将使用此默认值。
 *
 * @author zhaojun
 */
public class StorageSourceConnectionProperties {

    /**
     * 默认连接超时时间（单位：秒）。
     * 当向存储源（如 S3、OneDrive 等）发起连接请求时，如果在 5 秒内未建立连接，则视为超时。
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 5;

    /**
     * 默认连接超时时间（单位：毫秒）。
     * 由 {@link #DEFAULT_CONNECTION_TIMEOUT_SECONDS} 乘以 1000 得出，即 5000 毫秒（5 秒）。
     * 提供给使用毫秒作为时间单位的 HTTP 客户端库使用。
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = DEFAULT_CONNECTION_TIMEOUT_SECONDS * 1000;

}
