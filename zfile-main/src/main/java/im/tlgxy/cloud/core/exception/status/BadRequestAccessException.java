package im.tlgxy.cloud.core.exception.status;

import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.core.exception.core.BizException;

/**
 * 错误请求异常（HTTP 400）
 * <p>
 * 当客户端发送的请求参数有误或服务器无法理解该请求时抛出此异常，
 * 通常对应 HTTP 400 Bad Request 状态码。
 * </p>
 * <p>
 * 典型场景：
 * <ul>
 *   <li>请求参数格式错误（如期望数字却传入了字符串）</li>
 *   <li>缺少必填的请求参数</li>
 *   <li>请求体格式不正确（如 JSON 解析失败）</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#badRequestAccessException(BadRequestAccessException)}
 * </p>
 *
 * @author zhaojun
 */
public class BadRequestAccessException extends BizException {

    /**
     * 构造错误请求异常
     *
     * @param message 错误描述信息
     */
    public BadRequestAccessException(String message) {
        super(message);
    }
}
