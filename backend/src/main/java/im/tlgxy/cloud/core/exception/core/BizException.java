package im.tlgxy.cloud.core.exception.core;

import im.tlgxy.cloud.core.exception.ErrorCode;
import lombok.Getter;

/**
 * 业务异常基类
 * <p>
 * 所有业务相关异常的父类。业务异常属于正常业务流程中可能出现的异常，
 * 如用户名密码错误、未登录、参数校验失败等。这类异常用户可以自行处理，
 * 系统默认不打印堆栈跟踪信息（可通过 {@link #printExceptionStackTrace()} 覆盖）。
 * </p>
 * <p>
 * 与 {@link SystemException} 的区别：
 * <ul>
 *   <li>BizException：正常业务流程中的异常，用户可以理解和自行处理，默认不打印堆栈</li>
 *   <li>SystemException：系统级异常，用户无法处理，需要记录日志供管理员排查</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 8312907182931723379L;

    /**
     * 错误码，通常对应 {@link ErrorCode} 枚举中的某个值
     */
    private String code;

    /**
     * 判断是否需要打印异常堆栈信息
     * <p>
     * 业务异常默认不打印堆栈信息，以减少日志量。
     * 如果某个子类异常确实需要打印堆栈（如排查复杂的配置问题），
     * 可以覆盖此方法返回 true。
     * </p>
     *
     * @return 默认返回 false，表示不打印堆栈跟踪
     */
    public boolean printExceptionStackTrace() {
        return false;
    }

    /**
     * 构造一个没有错误信息的业务异常
     */
    public BizException() {
        super();
    }

    /**
     * 使用指定的原始异常构造业务异常
     *
     * @param cause 错误原因，通过 {@link Throwable#getCause()} 方法可以获取传入的 cause 信息
     */
    public BizException(Throwable cause) {
        super(cause);
    }

    /**
     * 使用错误信息构造业务异常
     *
     * @param message 错误描述信息
     */
    public BizException(String message) {
        super(message);
    }

    /**
     * 使用错误码和错误信息构造业务异常
     *
     * @param code    错误码
     * @param message 错误描述信息
     */
    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用错误信息和原始异常构造业务异常
     *
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public BizException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用错误码、错误信息和原始异常构造业务异常
     *
     * @param code    错误码
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public BizException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 通过 {@link ErrorCode} 枚举构造业务异常
     *
     * @param errorCode 错误码枚举，同时提供错误码和错误信息
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 通过 {@link ErrorCode} 枚举和原始异常构造业务异常
     *
     * @param errorCode 错误码枚举
     * @param cause     导致此异常的原始异常
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }

}
