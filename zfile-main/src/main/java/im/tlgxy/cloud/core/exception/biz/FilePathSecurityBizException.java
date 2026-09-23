package im.tlgxy.cloud.core.exception.biz;


import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import lombok.Getter;

/**
 * 文件路径安全异常
 * <p>
 * 当用户提供的文件路径存在安全风险时抛出此异常，主要包括以下情况：
 * <ul>
 *   <li>路径中包含 {@code ./} 或 {@code ../} 等相对路径字符，可能导致目录遍历攻击</li>
 *   <li>路径中包含特殊字符或操作系统敏感字符</li>
 *   <li>路径试图访问系统不允许的目录范围</li>
 * </ul>
 * 此异常的错误码为 {@link ErrorCode#BIZ_FILE_PATH_ILLEGAL}。
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#filePathSecurityBizException(FilePathSecurityBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class FilePathSecurityBizException extends BizException {

    /**
     * 存在安全风险的原始文件路径
     */
    private final String path;

    /**
     * 构造文件路径安全异常
     *
     * @param path 存在安全风险的文件路径
     */
    public FilePathSecurityBizException(String path) {
        super(ErrorCode.BIZ_FILE_PATH_ILLEGAL);
        this.path = path;
    }

}
