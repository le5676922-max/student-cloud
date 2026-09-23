package im.tlgxy.cloud.module.share.model.request;

import im.tlgxy.cloud.core.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取分享 OnlyOffice 预览配置 Token 的请求对象
 * <p>
 * 用于在分享页面中请求 OnlyOffice 在线预览服务所需的配置令牌。
 * 需要提供分享链接标识、文件路径以及相关的密码验证信息。
 * 通过 {@link #handleDefaultValue()} 方法对文件路径进行规范化处理，
 * 确保路径格式统一。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "获取分享 OnlyOffice 配置请求")
public class ShareOnlyOfficeConfigTokenRequest {

    /**
     * 分享链接的唯一标识 key，用于定位具体的分享记录
     */
    @Schema(title = "分享链接 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "abc12345")
    @NotBlank(message = "分享链接 key 不能为空")
    private String shareKey;

    /**
     * 分享内需要预览的文件路径，相对于分享根目录
     * 需要指向具体文件（如 Office 文档），不能是目录
     */
    @Schema(title = "分享内文件路径", requiredMode = Schema.RequiredMode.REQUIRED, example = "/office/文档.docx")
    @NotBlank(message = "文件路径不能为空")
    private String path;

    /**
     * 分享密码（可选），用于验证设置了密码保护的分享链接
     */
    @Schema(title = "分享密码", example = "123456")
    private String sharePassword;

    /**
     * 目录密码（可选），用于验证设置了单独密码保护的子目录
     */
    @Schema(title = "目录密码", example = "123456")
    private String folderPassword;

    /**
     * 处理请求参数的默认值
     * <p>
     * 调用 {@link StringUtils#concat(String)} 对文件路径进行规范化处理，
     * 确保路径以 "/" 开头和结尾，统一路径格式，避免因路径格式不一致导致的文件定位失败。
     * </p>
     */
    public void handleDefaultValue() {
        // 统一路径规则，确保路径格式规范（以 "/" 开头和结尾）
        path = StringUtils.concat(path);
    }

}
