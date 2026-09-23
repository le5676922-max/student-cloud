package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.core.exception.core.BizException;
import lombok.Getter;

/**
 * 存储源初始化异常
 * <p>
 * 当存储源（如本地文件系统、FTP、SFTP、S3、OneDrive 等）在初始化过程中失败时抛出此异常。
 * 初始化失败可能的原因包括：
 * <ul>
 *   <li>存储源配置信息错误（如账号密码错误、连接地址无效）</li>
 *   <li>网络不可达，无法连接到目标存储服务</li>
 *   <li>存储源认证失败，API 密钥无效或过期</li>
 *   <li>存储源服务本身不可用</li>
 * </ul>
 * 此异常会记录存储源 ID，便于管理员快速定位问题存储源。
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#initializeStorageSourceBizException(InitializeStorageSourceBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class InitializeStorageSourceBizException extends BizException {

    /**
     * 初始化失败的存储源 ID
     */
    private final Integer storageId;

    /**
     * 构造存储源初始化异常（仅消息）
     *
     * @param message   异常描述信息
     * @param storageId 初始化失败的存储源 ID
     */
    public InitializeStorageSourceBizException(String message, Integer storageId) {
        super(message);
        this.storageId = storageId;
    }

    /**
     * 构造存储源初始化异常（包含错误码、消息和根因）
     *
     * @param code      错误码
     * @param message   异常描述信息
     * @param storageId 初始化失败的存储源 ID
     * @param cause     导致初始化失败的原始异常
     */
    public InitializeStorageSourceBizException(String code, String message, Integer storageId, Throwable cause) {
        super(code, message, cause);
        this.storageId = storageId;
    }

}
