package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import lombok.Getter;

/**
 * 第三方 API 请求业务异常
 * <p>
 * 当系统调用第三方 API（如 OneDrive、SharePoint、S3 等云存储 API）时，
 * 如果返回的 HTTP 状态码不是 2xx，则抛出此异常。
 * 此异常会记录以下关键信息用于问题排查：
 * <ul>
 *   <li>请求的 API 地址（url）</li>
 *   <li>API 返回的 HTTP 状态码（responseCode）</li>
 *   <li>API 返回的响应体内容（responseBody）</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#apiHttpRequestBizException(APIHttpRequestBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class APIHttpRequestBizException extends BizException {

    /**
     * 请求的第三方 API 地址
     */
    private final String url;

    /**
     * 第三方 API 返回的 HTTP 响应状态码
     */
    private final int responseCode;

    /**
     * 第三方 API 返回的响应体内容
     */
    private final String responseBody;

    /**
     * 构造 API 请求业务异常
     *
     * @param errorCode    错误码枚举
     * @param url          请求的 API 地址
     * @param responseCode API 返回的 HTTP 状态码
     * @param responseBody API 返回的响应内容
     */
    public APIHttpRequestBizException(ErrorCode errorCode, String url, int responseCode, String responseBody) {
        super(errorCode);
        this.url = url;
        this.responseCode = responseCode;
        this.responseBody = responseBody;
    }

}
