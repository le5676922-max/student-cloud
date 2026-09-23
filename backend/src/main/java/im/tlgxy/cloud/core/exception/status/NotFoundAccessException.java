package im.tlgxy.cloud.core.exception.status;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;

/**
 * 资源未找到异常（HTTP 404）
 * <p>
 * 当用户请求的资源在服务器上不存在时抛出此异常，
 * 通常对应 HTTP 404 Not Found 状态码。
 * </p>
 * <p>
 * 典型场景：
 * <ul>
 *   <li>请求的文件在存储源中不存在</li>
 *   <li>请求的短链/直链对应的资源已被删除</li>
 *   <li>请求的 API 路径不存在</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link im.tlgxy.cloud.core.exception.GlobalExceptionHandler#notFoundAccessException}
 * </p>
 *
 * @author zhaojun
 */
public class NotFoundAccessException extends BizException {

    /**
     * 构造资源未找到异常
     *
     * @param errorCode 错误码枚举
     */
    public NotFoundAccessException(ErrorCode errorCode) {
        super(errorCode);
    }

}
