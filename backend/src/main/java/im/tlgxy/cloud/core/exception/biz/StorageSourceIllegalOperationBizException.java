package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import lombok.Getter;

/**
 * 存储源非法操作异常
 * <p>
 * 当用户对存储源执行了不被允许的操作时抛出此异常。例如：
 * <ul>
 *   <li>对只读存储源执行上传、删除、重命名等修改操作</li>
 *   <li>执行了存储源不支持的文件操作类型</li>
 *   <li>未授权的用户尝试执行管理类操作</li>
 * </ul>
 * 异常中记录了存储源 ID 和具体的操作类型，便于审计和问题排查。
 * 错误码为 {@link ErrorCode#BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION}。
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#storageSourceIllegalOperationBizException(StorageSourceIllegalOperationBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class StorageSourceIllegalOperationBizException extends BizException {

    /**
     * 被操作的存储源 ID
     */
    private final Integer storageId;

    /**
     * 尝试执行的操作类型（如上传、删除、重命名等）
     */
    private final FileOperatorTypeEnum action;

    /**
     * 构造存储源非法操作异常
     *
     * @param storageId 存储源 ID
     * @param action    尝试执行的操作类型枚举
     */
    public StorageSourceIllegalOperationBizException(Integer storageId, FileOperatorTypeEnum action) {
        super(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION);
        this.storageId = storageId;
        this.action = action;
    }
}
