package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.core.BizException;
import lombok.Getter;

/**
 * 跨域请求业务异常
 * <p>
 * 当跨域请求被拒绝或跨域配置出现问题时抛出此异常。
 * 与普通业务异常不同，此异常需要打印完整的堆栈跟踪信息，
 * 因为跨域问题通常由配置错误引起，需要详细日志来定位根因。
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class CorsBizException extends BizException {

    /**
     * 构造跨域异常
     *
     * @param message 异常描述信息
     * @param cause   导致异常的根因
     */
    public CorsBizException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 覆盖父类方法，跨域异常需要打印完整的堆栈信息以帮助问题排查
     *
     * @return 始终返回 true，表示需要打印堆栈跟踪
     */
    @Override
    public boolean printExceptionStackTrace() {
        return true;
    }

}
