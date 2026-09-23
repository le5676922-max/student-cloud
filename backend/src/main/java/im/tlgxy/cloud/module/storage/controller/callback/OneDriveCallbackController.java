package im.tlgxy.cloud.module.storage.controller.callback;

import cn.hutool.core.codec.Base64;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.model.dto.OAuth2TokenDTO;
import im.tlgxy.cloud.module.storage.oauth2.service.OneDriveChinaOAuth2ServiceImpl;
import im.tlgxy.cloud.module.storage.oauth2.service.OneDriveOAuth2ServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * OneDrive OAuth2 认证回调控制器。
 *
 * 负责处理 OneDrive（国际版和世纪互联版）的 OAuth2 授权流程。
 * OneDrive 分为两个版本：
 * 1. 国际版（个人版、家庭版等非世纪互联运营的版本）—— 走微软全球 OAuth2 端点；
 * 2. 世纪互联版（中国版，由 21Vianet 运营）—— 走微软中国 OAuth2 端点。
 *
 * 两个版本使用不同的 OAuth2 服务实现和不同的授权/回调接口。
 *
 * 授权流程：
 * 1. 前端填写 clientId、clientSecret、redirectUri；
 * 2. 调用 /authorize 或 /china-authorize 生成授权 URL 并重定向；
 * 3. 用户在微软授权页面登录并授权；
 * 4. 微软回调 /callback 或 /china-callback，携带授权码；
 * 5. 系统使用授权码换取 accessToken 和 refreshToken。
 *
 * 注意：RequestMapping 中同时映射了 "/onedrive" 和 "/onedirve"（包含一个拼写错误的历史路径兼容）。
 *
 * @author zhaojun
 */
@Tag(name = "OneDrive 认证回调模块")
@Controller
@Slf4j
@RequestMapping(value = {"/onedrive", "/onedirve"})
public class OneDriveCallbackController {

    /**
     * OneDrive 国际版 OAuth2 服务实现。
     */
    @Resource
    private OneDriveOAuth2ServiceImpl oneDriveOAuth2Service;

    /**
     * OneDrive 世纪互联版（中国版）OAuth2 服务实现。
     */
    @Resource
    private OneDriveChinaOAuth2ServiceImpl oneDriveChinaOAuth2Service;

    /**
     * 生成 OneDrive 国际版的 OAuth2 授权 URL 并重定向用户到微软授权页面。
     *
     * @param   clientId
     *          微软 Azure 应用注册中的应用程序（客户端）ID
     *
     * @param   clientSecret
     *          微软 Azure 应用注册中的客户端密码
     *
     * @param   redirectUri
     *          OAuth2 授权成功后的回调地址
     *
     * @return  重定向到微软授权页面的指令字符串
     */
    @GetMapping("/authorize")
    @ApiOperationSupport(order = 1)
    @Operation(summary = "生成 OAuth2 登陆 URL", description = "生成 OneDrive OAuth2 登陆 URL，用户国际版，家庭版等非世纪互联运营的 OneDrive.")
    public String authorize(String clientId, String clientSecret, String redirectUri) {
        String authorizeUrl = oneDriveOAuth2Service.generateAuthorizationUrl(clientId, clientSecret, redirectUri);
        return "redirect:" + authorizeUrl;
    }

    /**
     * OneDrive 国际版 OAuth2 授权回调接口。
     *
     * 用户在微软授权页面完成授权后，微软会重定向回此接口并携带授权码。
     * 从 state 参数中解析出原始配置信息，使用授权码换取 OAuth2 Token。
     *
     * @param   code
     *          微软返回的授权码
     *
     * @param   state
     *          授权前传入的 Base64 编码状态信息，格式为 "clientId::clientSecret::redirectUri"
     *
     * @param   model
     *          Spring MVC Model 对象，用于向视图传递 Token 数据
     *
     * @return  渲染 callback 视图页面
     */
    @GetMapping("/callback")
    @ApiOperationSupport(order = 2)
    @Operation(summary = "OAuth2 回调地址", description = "根据 OAuth2 协议，登录成功后，会返回给网站一个 code，用此 code 去换取 accessToken 和 refreshToken.（oneDrive 会回调此接口）")
    public String oneDriveCallback(String code, String state, Model model) {
        if (log.isDebugEnabled()) {
            log.debug("onedrive 国际版授权回调参数信息： code: {}, state: {}", code, state);
        }

        // 从 state 中解析 OAuth2 配置参数
        String clientId = null, clientSecret = null, redirectUri = null;
        if (StringUtils.isNotEmpty(state)) {
            String stateDecode = Base64.decodeStr(state);
            String[] stateArr = stateDecode.split("::");
            clientId = stateArr[0];
            clientSecret = stateArr[1];
            redirectUri = stateArr[2];
        }

        // 用授权码换取 accessToken 和 refreshToken
        OAuth2TokenDTO oAuth2TokenDTO = oneDriveOAuth2Service.getTokenByCode(code, clientId, clientSecret, redirectUri);
        model.addAttribute("oauth2Token", oAuth2TokenDTO);
        model.addAttribute("type", "OneDrive 国际版");
        return "callback";
    }

    /**
     * 生成 OneDrive 世纪互联版（中国版）的 OAuth2 授权 URL。
     *
     * 与国际版类似，但使用世纪互联专属的 OAuth2 端点。
     *
     * @param   clientId
     *          世纪互联 Azure 应用注册中的客户端 ID
     *
     * @param   clientSecret
     *          世纪互联 Azure 应用注册中的客户端密码
     *
     * @param   redirectUri
     *          授权成功后的回调地址
     *
     * @return  重定向到世纪互联微软授权页面
     */
    @GetMapping("/china-authorize")
    @ApiOperationSupport(order = 3)
    @Operation(summary = "生成 OAuth2 登陆 URL(世纪互联)", description = "生成 OneDrive OAuth2 登陆 URL，用于世纪互联版本.")
    public String authorizeChina(String clientId, String clientSecret, String redirectUri) {
        String authorizeUrl = oneDriveChinaOAuth2Service.generateAuthorizationUrl(clientId, clientSecret, redirectUri);
        return "redirect:" + authorizeUrl;
    }

    /**
     * OneDrive 世纪互联版 OAuth2 授权回调接口。
     *
     * 处理流程与国际版回调相同，但使用世纪互联专属的 OAuth2 服务实现来换取 Token。
     *
     * @param   code
     *          世纪互联返回的授权码
     *
     * @param   state
     *          授权前传入的 Base64 编码状态信息
     *
     * @param   model
     *          Spring MVC Model 对象
     *
     * @return  渲染 callback 视图页面
     */
    @GetMapping("/china-callback")
    @ApiOperationSupport(order = 4)
    @Operation(summary = "OAuth2 回调地址(世纪互联)", description = "根据 OAuth2 协议，登录成功后，会返回给网站一个 code，用此 code 去换取 accessToken 和 refreshToken.（oneDrive 会回调此接口）")
    public String oneDriveChinaCallback(String code, String state, Model model) {
        if (log.isDebugEnabled()) {
            log.debug("onedrive 世纪互联授权回调参数信息： code: {}, state: {}", code, state);
        }

        // 从 state 中解析 OAuth2 配置参数
        String clientId = null, clientSecret = null, redirectUri = null;
        if (StringUtils.isNotEmpty(state)) {
            String stateDecode = Base64.decodeStr(state);
            String[] stateArr = stateDecode.split("::");
            clientId = stateArr[0];
            clientSecret = stateArr[1];
            redirectUri = stateArr[2];
        }

        // 用授权码向世纪互联端点换取 Token
        OAuth2TokenDTO OAuth2TokenDTO = oneDriveChinaOAuth2Service.getTokenByCode(code, clientId, clientSecret, redirectUri);
        model.addAttribute("oauth2Token", OAuth2TokenDTO);
        model.addAttribute("type", "OneDrive 世纪互联");
        return "callback";
    }

}
