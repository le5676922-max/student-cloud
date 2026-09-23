package im.tlgxy.cloud.core.exception.core;

import im.tlgxy.cloud.core.exception.ErrorCode;
import lombok.Getter;

/**
 * 系统异常基类
 * <p>
 * 所有系统级异常的父类。系统异常是用户无法自行处理的异常，如网络异常、服务器内部错误、
 * 数据库连接失败等。这类异常需要记录详细的日志信息（包括堆栈跟踪），供管理员排查问题。
 * </p>
 * <p>
 * 与 {@link BizException} 的区别：
 * <ul>
 *   <li>SystemException：系统级严重错误，用户无法处理，必须记录完整日志</li>
 *   <li>BizException：业务流程中的正常异常，用户可理解和处理，默认不打印堆栈</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class SystemException extends RuntimeException {

    private static final long serialVersionUID = 8312907182931723379L;

    /**
     * 错误码，通常对应 {@link ErrorCode} 枚举中以 "5" 开头的系统错误码
     */
    private String code;

    /**
     * 构造一个没有错误信息的系统异常（较少使用）
     */
    public SystemException() {
        super();
    }

    /**
     * 使用指定的原始异常构造系统异常，异常信息取自 cause.toString()
     *
     * @param cause 错误原因，通过 {@link Throwable#getCause()} 方法可以获取传入的 cause 信息
     */
    public SystemException(Throwable cause) {
        super(cause);
    }

    /**
     * 使用错误信息构造系统异常
     *
     * @param message 错误描述信息
     */
    public SystemException(String message) {
        super(message);
    }

    /**
     * 使用错误码和错误信息构造系统异常
     *
     * @param code    错误码
     * @param message 错误描述信息
     */
    public SystemException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用错误信息和原始异常构造系统异常
     *
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用错误码、错误信息和原始异常构造系统异常
     *
     * @param code    错误码
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public SystemException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 通过 {@link ErrorCode} 枚举构造系统异常
     *
     * @param errorCode 错误码枚举，自动提取 code 和 message
     */
    public SystemException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 通过 {@link ErrorCode} 枚举和原始异常构造系统异常
     *
     * @param errorCode 错误码枚举
     * @param cause     导致此异常的原始异常
     */
    public SystemException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }

}
