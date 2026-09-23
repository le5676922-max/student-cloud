package im.tlgxy.cloud.core.exception.status;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.core.exception.core.BizException;

/**
 * 方法不允许异常（HTTP 405）
 * <p>
 * 当客户端使用了目标资源不支持的 HTTP 方法时抛出此异常，
 * 通常对应 HTTP 405 Method Not Allowed 状态码。
 * </p>
 * <p>
 * 典型场景：
 * <ul>
 *   <li>对只接受 GET 请求的接口使用了 POST 方法</li>
 *   <li>对只接受 POST 请求的接口使用了 GET 方法</li>
 *   <li>使用了 PUT/DELETE 等接口不支持的方法</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#methodNotAllowedAccessException(MethodNotAllowedAccessException)}
 * </p>
 *
 * @author zhaojun
 */
public class MethodNotAllowedAccessException extends BizException {

    /**
     * 构造方法不允许异常
     *
     * @param errorCode 错误码枚举
     */
    public MethodNotAllowedAccessException(ErrorCode errorCode) {
        super(errorCode);
    }
}
