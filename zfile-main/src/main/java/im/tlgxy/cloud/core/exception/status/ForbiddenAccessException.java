package im.tlgxy.cloud.core.exception.status;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;

/**
 * 禁止访问异常（HTTP 403）
 * <p>
 * 当用户已经通过身份认证（已登录），但不具备访问特定资源的权限时抛出此异常，
 * 通常对应 HTTP 403 Forbidden 状态码。
 * </p>
 * <p>
 * 与 {@link UnauthorizedAccessException}（401）的区别：
 * <ul>
 *   <li>403 Forbidden：用户已登录，但权限不足（如普通用户访问管理员功能）</li>
 *   <li>401 Unauthorized：用户未登录或认证信息无效</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link im.tlgxy.cloud.core.exception.GlobalExceptionHandler#forbiddenAccessException}
 * </p>
 *
 * @author zhaojun
 */
public class ForbiddenAccessException extends BizException {

    /**
     * 构造禁止访问异常
     *
     * @param errorCode 错误码枚举，指定具体的无权限错误类型
     */
    public ForbiddenAccessException(ErrorCode errorCode) {
        super(errorCode);
    }

}
