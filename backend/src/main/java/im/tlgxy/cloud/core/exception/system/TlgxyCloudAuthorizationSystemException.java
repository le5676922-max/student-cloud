package im.tlgxy.cloud.core.exception.system;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.core.exception.core.SystemException;

/**
 * TlgxyCloud 授权验证系统异常
 * <p>
 * 当 TlgxyCloud 系统的授权码验证失败时抛出此异常。属于系统级异常，
 * 通常表示授权码无效、过期或授权验证服务不可用等问题。
 * </p>
 * <p>
 * 典型场景：
 * <ul>
 *   <li>未配置授权码或授权码为空</li>
 *   <li>授权码格式不正确或被篡改</li>
 *   <li>授权验证请求失败（如网络问题）</li>
 *   <li>服务器时间异常导致授权验证失败</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#tlgxyCloudAuthorizationSystemException(TlgxyCloudAuthorizationSystemException)}
 * </p>
 *
 * @author zhaojun
 */
public class TlgxyCloudAuthorizationSystemException extends SystemException {

    /**
     * 使用错误码和错误信息构造授权异常
     *
     * @param code    错误码
     * @param message 错误描述信息
     */
    public TlgxyCloudAuthorizationSystemException(String code, String message) {
        super(code, message);
    }

    /**
     * 通过 {@link ErrorCode} 枚举构造授权异常
     *
     * @param errorCode 错误码枚举，如 PRO_AUTH_CODE_EMPTY、PRO_AUTH_CODE_INVALID_ERROR 等
     */
    public TlgxyCloudAuthorizationSystemException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 通过 {@link ErrorCode} 枚举和原始异常构造授权异常
     *
     * @param errorCode 错误码枚举
     * @param cause     导致授权验证失败的原始异常
     */
    public TlgxyCloudAuthorizationSystemException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

}
