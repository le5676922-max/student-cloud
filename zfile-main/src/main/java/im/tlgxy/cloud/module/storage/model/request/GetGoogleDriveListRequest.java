package im.tlgxy.cloud.module.storage.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * Google Drive 驱动器列表请求类
 * <p>
 * 用于请求获取 Google Drive 中所有可用的驱动器（Drive）列表。
 * 需要提供有效的 Google OAuth2 访问令牌（accessToken）进行身份验证。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="gd drive 列表请求类")
public class GetGoogleDriveListRequest {

	/**
	 * Google OAuth2 访问令牌，用于身份验证和授权
	 * 不能为空，否则无法访问 Google Drive API
	 */
	@NotBlank(message = "accessToken 不能为空")
	@Schema(title = "accessToken", requiredMode = Schema.RequiredMode.REQUIRED, example = "v7LtfjIbnxLCTj0R3riwhyxcbv4KVH5HuPWHWrrewHMEwjJyUlYXV6D4m1MLJ2dP__GX_7CKCc-HudUetPXWS2wwbfkNs6ydLq3xrk1gHA7wcD_pmt6oNuRXw5mnFzfdLkH5wIG1suQp3p0eHJurzIaCgYKATASATASFQE65dr8hO725r41QtZc9RJVUg12cA0163")
	private String accessToken;

}
