package im.tlgxy.cloud.core.exception.system;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import im.tlgxy.cloud.core.exception.core.SystemException;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import lombok.Getter;


/**
 * 文件上传失败系统异常
 * <p>
 * 当文件上传到存储源（本地、FTP、S3、OneDrive 等）失败时抛出此异常。
 * 属于系统级异常，用户无法自行处理，需要管理员介入排查。
 * </p>
 * <p>
 * 该异常记录了丰富的上下文信息用于问题排查：
 * <ul>
 *   <li><b>storageTypeEnum</b>：存储源类型（如 FTP、S3、OneDrive），帮助确定是哪种存储出了问题</li>
 *   <li><b>uploadPath</b>：目标上传路径，帮助定位具体文件和目录</li>
 *   <li><b>inputStreamAvailable</b>：上传时输入流中可用的字节数，帮助判断是文件读取问题还是上传问题</li>
 *   <li><b>responseCode</b>：第三方存储 API 返回的 HTTP 响应码（如 403、500 等）</li>
 *   <li><b>responseBody</b>：第三方存储 API 返回的响应体内容</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#uploadFileFailSystemException(UploadFileFailSystemException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class UploadFileFailSystemException extends SystemException {

    /**
     * 存储源类型枚举，标识是哪种存储源上传失败
     */
    private final StorageTypeEnum storageTypeEnum;

    /**
     * 尝试上传的目标路径
     */
    private final String uploadPath;

    /**
     * 上传输入流中可用的字节数（用于判断流是否为空或已关闭）
     */
    private final Long inputStreamAvailable;

    /**
     * 第三方存储服务的 HTTP 响应状态码
     */
    private final int responseCode;

    /**
     * 第三方存储服务返回的响应体内容
     */
    private final String responseBody;

    /**
     * 构造文件上传失败异常（无原始异常）
     *
     * @param storageTypeEnum     存储源类型
     * @param uploadPath          上传目标路径
     * @param inputStreamAvailable 输入流可用字节数
     * @param responseCode        响应状态码
     * @param responseBody        响应体内容
     */
    public UploadFileFailSystemException(StorageTypeEnum storageTypeEnum, String uploadPath, Long inputStreamAvailable, int responseCode, String responseBody) {
        this(storageTypeEnum, uploadPath, inputStreamAvailable, responseCode, responseBody, null);
    }

    /**
     * 构造文件上传失败异常（包含原始异常）
     *
     * @param storageTypeEnum     存储源类型
     * @param uploadPath          上传目标路径
     * @param inputStreamAvailable 输入流可用字节数
     * @param responseCode        响应状态码
     * @param responseBody        响应体内容
     * @param cause               导致上传失败的原始异常
     */
    public UploadFileFailSystemException(StorageTypeEnum storageTypeEnum, String uploadPath, Long inputStreamAvailable, int responseCode, String responseBody, Throwable cause) {
        super(ErrorCode.BIZ_UPLOAD_FILE_ERROR, cause);
        this.storageTypeEnum = storageTypeEnum;
        this.uploadPath = uploadPath;
        this.inputStreamAvailable = inputStreamAvailable;
        this.responseCode = responseCode;
        this.responseBody = responseBody;
    }

}
