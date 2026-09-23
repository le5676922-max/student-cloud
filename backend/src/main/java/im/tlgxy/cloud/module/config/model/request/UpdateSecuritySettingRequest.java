package im.tlgxy.cloud.module.config.model.request;

import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录安全设置请求参数类。
 * <p>
 * 用于后台管理页面修改登录安全相关的配置，包括：
 * <ul>
 *   <li>安全登录入口 —— 自定义后台登录页面的 URL 路径，隐藏默认入口</li>
 *   <li>登录验证码 —— 是否启用图形验证码</li>
 *   <li>双因素认证（2FA）—— 为管理员启用 TOTP 二次验证</li>
 *   <li>登录日志模式 —— 记录所有登录或仅记录异常登录</li>
 *   <li>前台登录按钮 —— 是否在前台页面显示登录入口</li>
 *   <li>访客首页 —— 匿名用户访问时显示的首页内容</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "登陆安全设置请求参数类")
public class UpdateSecuritySettingRequest {

	/**
	 * 是否在前台页面显示登录按钮
	 */
	@Schema(title = "是否在前台显示登陆按钮", example = "true")
	private Boolean showLogin;

	/**
	 * 安全登录入口路径，仅允许字母、数字、短横线和下划线（最大 32 字符）
	 */
	@Schema(title = "安全登录入口", description = "仅允许字母、数字、短横线和下划线，长度不超过 32", example = "admin")
	@Size(max = 32, message = "安全登录入口长度不能超过 32 个字符")
	@Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "安全登录入口只能包含字母、数字、短横线和下划线")
	private String secureLoginEntry;

	/**
	 * 登录日志记录模式（如：记录所有、仅记录异常、不记录）
	 */
	@Schema(title = "登录日志模式", example = "all")
	private LoginLogModeEnum loginLogMode;

	/**
	 * 是否启用登录图形验证码
	 */
	@Schema(title = "是否启用登陆验证码", example = "true")
	private Boolean loginImgVerify;

	/**
	 * 是否为管理员启用双因素认证（TOTP）
	 */
	@Schema(title = "是否为管理员启用双因素认证", example = "true")
	private Boolean adminTwoFactorVerify;

	/**
	 * 双因素认证的 TOTP Secret 密钥
	 */
	@Schema(title = "2FA登陆验证 Secret")
	private String loginVerifySecret;

	/**
	 * 匿名用户访问首页时显示的 HTML 内容（用于自定义访客首页）
	 */
	@Schema(title = "匿名用户首页显示内容")
	private String guestIndexHtml;

}
