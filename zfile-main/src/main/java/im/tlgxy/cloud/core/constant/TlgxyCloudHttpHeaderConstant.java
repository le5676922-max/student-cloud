package im.tlgxy.cloud.core.constant;

/**
 * TlgxyCloud 自定义 HTTP 请求头常量
 * <p>
 * 定义了 TlgxyCloud 系统在前端与后端通信时使用的自定义 HTTP 请求头名称。
 * 这些请求头用于传递认证令牌、标识请求来源等特殊信息，避免与标准 HTTP 头冲突。
 * </p>
 *
 * @author zhaojun
 */
public class TlgxyCloudHttpHeaderConstant {

    /**
     * TlgxyCloud 认证令牌请求头
     * <p>
     * 前端在发送请求时携带此请求头，值为用户的认证 token，后端通过此 token 识别用户身份。
     * </p>
     */
    public static final String TLGXY_CLOUD_TOKEN = "TlgxyCloud-Token";

    /**
     * 标识是否为 Axios 发起的请求
     * <p>
     * 后端根据此请求头判断请求是由前端 Axios 库发起的 AJAX 请求，
     * 还是浏览器直接访问的页面请求，从而决定返回 JSON 数据还是重定向到错误页面。
     * </p>
     */
    public static final String AXIOS_REQUEST = "Axios-Request";

    /**
     * 标识 Axios 请求的来源页面
     * <p>
     * 用于记录发起请求的页面来源信息，便于后端进行请求追踪和日志记录。
     * </p>
     */
    public static final String AXIOS_FROM = "Axios-From";

}
