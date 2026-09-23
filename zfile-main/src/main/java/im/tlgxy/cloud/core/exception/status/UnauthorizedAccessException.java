package im.tlgxy.cloud.core.exception.status;

import im.tlgxy.cloud.core.exception.core.BizException;

/**
 * 未授权访问异常（HTTP 401）
 * <p>
 * 当用户未进行身份认证（未登录）或认证信息无效时抛出此异常，
 * 通常对应 HTTP 401 Unauthorized 状态码。
 * </p>
 * <p>
 * 与 {@link ForbiddenAccessException}（403）的区别：
 * <ul>
 *   <li>401 Unauthorized：用户未登录或登录凭证无效，需要先进行身份认证</li>
 *   <li>403 Forbidden：用户已登录但权限不足，即使重新认证也无法解决</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link im.tlgxy.cloud.core.exception.GlobalExceptionHandler#unauthorizedAccessException}
 * </p>
 *
 * @author zhaojun
 */
public class UnauthorizedAccessException extends BizException {

    /**
     * 构造未授权访问异常
     *
     * @param message 错误描述信息
     */
    public UnauthorizedAccessException(String message) {
        super(message);
    }

}
