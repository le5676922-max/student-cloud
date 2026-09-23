package im.tlgxy.cloud.module.storage.controller.callback;

import cn.hutool.core.codec.Base64;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.dto.OAuth2TokenDTO;
import im.tlgxy.cloud.module.storage.oauth2.service.IOAuth2Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Google Drive OAuth2 认证回调控制器。
 *
 * 负责处理 Google Drive 的 OAuth2 授权流程：
 * 1. 生成授权 URL 并重定向用户到 Google 的授权页面；
 * 2. 处理 Google 授权成功后的回调，用授权码换取 accessToken 和 refreshToken。
 *
 * Google Drive 使用 OAuth2 协议进行身份认证，用户在 Google 的授权页面登录并授权后，
 * Google 会回调此控制器中配置的回调地址，携带授权码（code）和状态信息（state）。
 * 系统使用授权码向 Google 换取访问令牌，并将令牌信息返回给前端页面展示。
 *
 * @author zhaojun
 */
@Tag(name = "Google Drive 认证回调模块")
@Controller
@Slf4j
@RequestMapping(value = {"/gd"})
public class GoogleDriveCallbackController {

	/**
	 * Google Drive OAuth2 认证服务实现。
	 */
	@Resource
	private IOAuth2Service googleDriveOAuth2ServiceImpl;

	/**
	 * 生成 Google Drive OAuth2 授权 URL 并重定向。
	 *
	 * 接收用户在前端填写的 clientId、clientSecret 和 redirectUri，
	 * 调用 OAuth2 服务生成 Google 的授权页面 URL，然后将用户浏览器重定向到该 URL。
	 *
	 * @param   clientId
	 *          Google Cloud Console 中创建的应用 Client ID
	 *
	 * @param   clientSecret
	 *          Google Cloud Console 中创建的应用 Client Secret
	 *
	 * @param   redirectUri
	 *          OAuth2 授权成功后的回调地址
	 *
	 * @return  重定向到 Google 授权页面的指令字符串
	 */
	@GetMapping("/authorize")
	@ApiOperationSupport(order = 1)
	@Operation(summary = "生成 OAuth2 登陆 URL", description = "生成 OneDrive OAuth2 登陆 URL，用户国际版，家庭版等非世纪互联运营的 OneDrive.")
	public String authorize(String clientId, String clientSecret, String redirectUri) {
		String authorizeUrl = googleDriveOAuth2ServiceImpl.generateAuthorizationUrl(clientId, clientSecret, redirectUri);
		return "redirect:" + authorizeUrl;
	}

	/**
	 * Google Drive OAuth2 授权回调接口。
	 *
	 * 用户在 Google 授权页面完成授权后，Google 会重定向回此接口，携带授权码。
	 * 此方法从 state 参数中解析出原始的 clientId、clientSecret 和 redirectUri，
	 * 然后使用授权码向 Google 换取 OAuth2 Token，最后将 Token 信息渲染到页面展示。
	 *
	 * @param   code
	 *          Google 返回的授权码，用于换取 accessToken
	 *
	 * @param   state
	 *          授权前传入的状态信息（Base64 编码），包含 clientId、clientSecret、redirectUri，
	 *          用 "::" 分隔
	 *
	 * @param   model
	 *          Spring MVC 的 Model 对象，用于向视图传递数据
	 *
	 * @return  渲染 callback 视图页面，显示获取到的 Token 信息
	 */
	@GetMapping("/callback")
	public String googleDriveCallback(String code, String state, Model model) {
		if (log.isDebugEnabled()) {
			log.debug("Google Drive 授权回调参数信息： code: {}, state: {}", code, state);
		}

		// 从 state 中解析 clientId、clientSecret 和 redirectUri
		String clientId = null, clientSecret = null, redirectUri = null;
		if (StringUtils.isNotEmpty(state)) {
			String stateDecode = Base64.decodeStr(state);
			String[] stateArr = stateDecode.split("::");
			clientId = stateArr[0];
			clientSecret = stateArr[1];
			redirectUri = stateArr[2];
		}

		// 用授权码换取 OAuth2 Token
		OAuth2TokenDTO oAuth2TokenDTO = googleDriveOAuth2ServiceImpl.getTokenByCode(code, clientId, clientSecret, redirectUri);
		model.addAttribute("oauth2Token", oAuth2TokenDTO);
		model.addAttribute("type", "Google Drive");
		return "callback";
	}
}
