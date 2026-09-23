package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import lombok.Getter;

/**
 * 存储源文件/目录禁止访问异常
 * <p>
 * 当用户尝试访问未被授权的存储源文件或目录时抛出此异常。
 * 例如：用户没有某个文件夹的读取权限，或者某个文件/目录被管理员设置为禁止访问。
 * 该异常的错误码为 {@link ErrorCode#BIZ_STORAGE_SOURCE_FILE_FORBIDDEN}。
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#storageSourceFileForbiddenAccessBizException(StorageSourceFileForbiddenAccessBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class StorageSourceFileForbiddenAccessBizException extends BizException {

    /**
     * 存储源 ID，标识哪个存储源的文件被禁止访问
     */
    private final Integer storageId;

    /**
     * 被禁止访问的文件或目录路径
     */
    private final String path;

    /**
     * 构造存储源文件禁止访问异常
     *
     * @param storageId 存储源 ID
     * @param path      被禁止访问的文件路径
     */
    public StorageSourceFileForbiddenAccessBizException(Integer storageId, String path) {
        super(ErrorCode.BIZ_STORAGE_SOURCE_FILE_FORBIDDEN);
        this.storageId = storageId;
        this.path = path;
    }
}
