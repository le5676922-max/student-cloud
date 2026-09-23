package im.tlgxy.cloud.module.storage.model.request;

import im.tlgxy.cloud.core.validation.StringListValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * SharePoint 搜索站点请求类
 * <p>
 * 用于根据关键字搜索 SharePoint 站点列表。
 * 支持国际版（Standard）和世纪互联版（China）两种账号类型，
 * 通过 Microsoft Graph API 进行站点搜索。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class SharePointSearchSitesRequest {

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

}
