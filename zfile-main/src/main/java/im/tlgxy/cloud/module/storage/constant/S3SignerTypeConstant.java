package im.tlgxy.cloud.module.storage.constant;

/**
 * S3 协议签名类型常量类。
 *
 * 定义了与 Amazon S3 兼容存储服务通信时使用的各种签名算法类型。
 * S3 协议要求客户端对请求进行签名以验证身份，不同的 S3 兼容存储服务可能支持不同的签名版本。
 *
 * 使用场景：在配置 S3 兼容存储源时，用户可以选择合适的签名类型来适配不同的对象存储服务。
 *
 * 各常量说明：
 * - DEFAULT：默认（空字符串），由 SDK 自动选择
 * - AWSS3V4SignerType：AWS S3 V4 签名器
 * - S3SignerType：较旧版本的 S3 签名器
 * - QueryStringSignerType：查询字符串签名器，用于预签名 URL
 * - AWS3SignerType：AWS 第三版签名器（已过时）
 * - AWS4SignerType：AWS 第四版签名器（当前主流）
 * - NoOpSignerType：无操作签名器（不签名，用于匿名访问）
 * - AWS4UnsignedPayloadSignerType：AWS V4 签名但不对请求体签名的签名器
 *
 * @author zhaojun
 */
public class S3SignerTypeConstant {

    /**
     * 默认签名类型（空字符串），由 AWS SDK 根据配置自动选择合适的签名算法。
     */
    public static final String DEFAULT = " ";

    /**
     * AWS S3 V4 签名器类型，适用于支持 V4 签名的 S3 兼容服务。
     */
    public static final String AWSS3V4SignerType = "AWSS3V4SignerType";

    /**
     * 较旧版本的 S3 签名器类型。
     */
    public static final String S3SignerType = "S3SignerType";

    /**
     * 查询字符串签名器类型，常用于生成带有签名的预签名 URL。
     */
    public static final String QueryStringSignerType = "QueryStringSignerType";

    /**
     * AWS 第三版签名器类型（已过时，不建议使用）。
     */
    public static final String AWS3SignerType = "AWS3SignerType";

    /**
     * AWS 第四版签名器类型，是目前主流的签名版本，提供更安全的请求签名。
     */
    public static final String AWS4SignerType = "AWS4SignerType";

    /**
     * 无操作签名器类型，不对请求进行签名，适用于允许匿名访问的存储桶。
     */
    public static final String NoOpSignerType = "NoOpSignerType";

    /**
     * AWS V4 签名但不校验请求体负载的签名器类型，适用于大文件上传等场景以减少计算开销。
     */
    public static final String AWS4UnsignedPayloadSignerType = "AWS4UnsignedPayloadSignerType";

}
