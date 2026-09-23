package im.tlgxy.cloud.module.share.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 创建分享链接的响应结果类
 * <p>
 * 当用户通过 API 创建分享链接成功后，后端返回此对象作为响应体。
 * 包含两个核心信息：分享链接的唯一标识 key 和可直接使用的完整分享 URL。
 * 前端可以直接展示完整分享 URL 给用户，方便用户复制并分享给他人。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "创建分享链接响应")
public class CreateShareLinkResult {

    /**
     * 分享链接的唯一标识 key
     * <p>
     * 由系统自动生成（基于 UUID 截取前 8 位）或用户自定义（3-8 位字母数字下划线短横线）。
     * 此 key 用于在 URL 中标识一个分享链接，例如 "/s/abc12345"。
     * </p>
     */
    @Schema(title = "分享链接 key", example = "abc12345")
    private String shareKey;

    /**
     * 完整的分享链接 URL
     * <p>
     * 由系统域名/代理域名 + "/share/" + shareKey 拼接而成的完整地址。
     * 用户可以直接复制此 URL 分享给他人，他人通过浏览器访问即可查看分享内容。
     * 例如：https://example.com/s/abc12345
     * </p>
     */
    @Schema(title = "完整分享链接", example = "https://example.com/s/abc12345")
    private String fullShareUrl;
}
