package im.tlgxy.cloud.core.exception.core;

import im.tlgxy.cloud.core.exception.ErrorCode;
import lombok.Getter;

/**
 * 错误页面业务异常
 * <p>
 * 与 {@link BizException} 类似，但此类异常被抛出时，系统会跳转到错误页面（如 500 错误页面）
 * 而不是返回 JSON 数据。一般用于浏览器直接访问的页面请求，而非 AJAX 请求。
 * </p>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>用户在浏览器中访问某个页面时发生错误，需要显示友好的错误页面</li>
 *   <li>错误码和错误信息可以通过 {@link #code} 和 {@link #getMessage()} 传递给错误页面</li>
 * </ul>
 * </p>
 * <p>
 * 注意：此类继承自 {@link RuntimeException} 而非 {@link BizException}，
 * 原因是需要独立的异常处理逻辑（跳转页面 vs 返回 JSON）。
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class ErrorPageBizException extends RuntimeException {

    private static final long serialVersionUID = 8312907182931723379L;

    /**
     * 错误码，用于标识错误类型
     */
    private String code;

    /**
     * 判断是否需要打印异常堆栈信息
     * <p>
     * 业务异常默认不打印堆栈信息。如需打印，子类可覆盖此方法返回 true。
     * </p>
     *
     * @return 默认返回 false
     */
    public boolean printExceptionStackTrace() {
        return false;
    }

    /**
     * 构造一个没有错误信息的异常
     */
    public ErrorPageBizException() {
        super();
    }

    /**
     * 使用指定的原始异常构造
     *
     * @param cause 错误原因，通过 {@link Throwable#getCause()} 方法可以获取传入的 cause 信息
     */
    public ErrorPageBizException(Throwable cause) {
        super(cause);
    }

    /**
     * 使用错误信息构造
     *
     * @param message 错误描述信息
     */
    public ErrorPageBizException(String message) {
        super(message);
    }

    /**
     * 使用错误码和错误信息构造
     *
     * @param code    错误码
     * @param message 错误描述信息
     */
    public ErrorPageBizException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用错误信息和原始异常构造
     *
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public ErrorPageBizException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用错误码、错误信息和原始异常构造
     *
     * @param code    错误码
     * @param message 错误描述信息
     * @param cause   导致此异常的原始异常
     */
    public ErrorPageBizException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 通过 {@link ErrorCode} 枚举构造
     *
     * @param errorCode 错误码枚举，自动提取 code 和 message
     */
    public ErrorPageBizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 通过 {@link ErrorCode} 枚举和原始异常构造
     *
     * @param errorCode 错误码枚举
     * @param cause     导致此异常的原始异常
     */
    public ErrorPageBizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }

}
