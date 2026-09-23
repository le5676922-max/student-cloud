package im.tlgxy.cloud.core.exception.biz;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.GlobalExceptionHandler;
import lombok.Getter;

/**
 * 预览文件内容获取异常
 * <p>
 * 当系统尝试获取文本文件内容用于在线预览时失败，抛出此异常。
 * 可能的失败原因包括：
 * <ul>
 *   <li>目标文件不存在或已被删除</li>
 *   <li>网络连接不可达，无法访问存储源</li>
 *   <li>文件读取权限不足</li>
 *   <li>存储源服务暂时不可用</li>
 * </ul>
 * </p>
 * <p>
 * 需要全局异常处理器捕获此异常，并记录日志。
 * 参见 {@link GlobalExceptionHandler#getPreviewTextContentBizException(GetPreviewTextContentBizException)}
 * </p>
 *
 * @author zhaojun
 */
@Getter
public class GetPreviewTextContentBizException extends BizException {

    /**
     * 获取预览文件的 URL 地址
     */
    private final String url;

    /**
     * 构造预览文件内容获取异常
     *
     * @param url   预览文件的 URL 地址
     * @param cause 导致预览失败的原始异常
     */
    public GetPreviewTextContentBizException(String url, Throwable cause) {
        super(cause);
        this.url = url;
    }

}
