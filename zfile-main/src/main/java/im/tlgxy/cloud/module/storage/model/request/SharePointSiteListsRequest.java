package im.tlgxy.cloud.module.storage.model.request;

import im.tlgxy.cloud.core.validation.StringListValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 获取 SharePoint 站点目录列表请求类
 * <p>
 * 用于请求获取指定 SharePoint 站点下的目录（List）列表。
 * 需要提供有效的访问令牌和目标站点 ID，站点 ID 支持两种格式：
 * 纯 UUID 格式或包含域名前缀的复合格式（会自动截取中间部分）。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class SharePointSiteListsRequest {

	/**
	 * 账号类型，只能是 Standard（标准版、国际版）或 China（世纪互联版）
	 */
	@StringListValue(message = "账号类型只能是 Standard（标准版、国际版）或 China（世纪互联）", vals = {"Standard", "China"})
	private String type;

	/**
	 * 访问令牌（accessToken），用于 Microsoft Graph API 的身份验证，不能为空
	 */
	@Schema(title = "访问令牌 (accessToken)", required = true, example = "EwBoxxxxxxxxxxxxxxxbAI=")
	@NotBlank(message = "访问令牌不能为空")
	private String accessToken;

	/**
	 * 站点 ID（siteId），支持两种格式：
	 * 1. 纯 UUID 格式：a046ac3a-ea74-13c5-8b8f-233599507d96
	 * 2. 复合格式：xxx.sharepoint.cn,a046ac3a-ea74-13c5-8b8f-233599507d96,ec7e71ed-9065-4190-b471-b91c28c30bb1
	 *    （复合格式会自动截取中间部分的 UUID）
	 */
	@Schema(title = "站点 ID (siteId)", required = true, example = "a046ac3a-ea74-13c5-8b8f-233599507d96 或 xxx.sharepoint.cn,a046ac3a-ea74-13c5-8b8f-233599507d96,ec7e71ed-9065-4190-b471-b91c28c30bb1  如果是后者，则会自动截取中间那部分，结果和前者相同")
	@NotBlank(message = "siteId 不能为空")
	private String siteId;

}
