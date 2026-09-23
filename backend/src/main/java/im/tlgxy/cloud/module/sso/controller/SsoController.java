package im.tlgxy.cloud.module.sso.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.URLUtil;
import im.tlgxy.cloud.module.sso.service.SsoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

/**
 * 单点登录跳转与回调控制器
 * <p>
 * 负责处理 SSO（单点登录）的核心流程：发起第三方登录请求和接收第三方回调。
 * 采用标准的 OAuth 2.0 / OIDC 授权码模式（Authorization Code Flow）：
 * </p>
 * <ol>
 *   <li>用户点击登录按钮，前端调用 {@code /sso/{provider}/login} 接口</li>
 *   <li>后端生成随机 state 防 CSRF 攻击，存入 HttpSession，然后重定向到第三方认证页面</li>
 *   <li>用户在第三方完成认证后，第三方回调 {@code /sso/{provider}/login/callback} 接口</li>
 *   <li>后端校验 state 一致性，使用授权码换取令牌，最终重定向到前端目标页面</li>
 * </ol>
 *
 * @author OnEvent
 */
@Slf4j
@Tag(name = "单点登录")
@RestController
@RequestMapping("/sso")
@RequiredArgsConstructor
class SsoController {

    /**
     * SSO 服务层，封装了各服务商的认证 URL 构建和回调处理逻辑
     */
    private final SsoService ssoService;

    /**
     * 发起 SSO 登录请求，重定向到第三方服务商的认证页面
     * <p>
     * 生成一个随机 state 值并存入 HttpSession，用于后续回调时校验请求来源，
     * 防止跨站请求伪造（CSRF）攻击。然后根据服务商标识 {@code provider}
     * 获取对应的 OAuth 认证地址，返回 303 See Other 重定向响应。
     * </p>
     *
     * @param provider 服务商标识（如 github、google、oidc 等），用于区分不同的第三方登录方式
     * @param session  当前用户的 HTTP 会话，用于存储 state 值
     * @return RedirectView 重定向视图，状态码为 303 See Other，目标地址为第三方认证页面
     */
    @GetMapping("/{provider}/login")
    @Operation(summary = "获取单点登录地址")
    public RedirectView login(@PathVariable String provider, HttpSession session) {
        // 生成随机 state 值，用作 CSRF 防护令牌
        String state = IdUtil.fastSimpleUUID();
        // 将 state 存入会话，供回调时校验
        session.setAttribute("state", state);

        // 根据服务商标识获取第三方认证的重定向 URL
        String url = ssoService.getAuthRedirectUrl(provider, state);

        // 构建 303 See Other 重定向响应
        RedirectView redirect = new RedirectView();
        redirect.setUrl(url);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    /**
     * SSO 登录回调接口，由第三方服务商在用户完成认证后调用
     * <p>
     * 该接口接收第三方返回的授权码（code）和状态码（state），完成以下校验流程：
     * </p>
     * <ol>
     *   <li>校验 HttpSession 中是否存在之前存储的 state，防止 session 过期或跨站攻击</li>
     *   <li>校验回调的 state 值与会话中存储的 state 是否一致，防止 CSRF 攻击</li>
     *   <li>校验通过后，使用授权码换取访问令牌并完成用户登录</li>
     *   <li>最终重定向到前端目标页面（通常是首页或登录前访问的页面）</li>
     * </ol>
     * <p>
     * 如果 state 校验失败，将重定向到错误页面并携带中文错误提示信息。
     * </p>
     *
     * @param provider 服务商标识，用于区分不同的第三方登录方式
     * @param code     第三方返回的授权码（authorization code），用于换取访问令牌
     * @param state    第三方回调时携带的状态码，需与发起请求时生成的 state 一致
     * @param session  当前用户的 HTTP 会话，用于获取之前存储的 state 值
     * @return RedirectView 重定向视图，校验成功则跳转到前端目标页面，失败则跳转到错误页面
     */
    @GetMapping("/{provider}/login/callback")
    @Operation(summary = "单点登录回调接口")
    public RedirectView callback(@PathVariable("provider") String provider,
                                 @RequestParam("code") String code,
                                 @RequestParam("state") String state,
                                 HttpSession session) {
        // 从当前会话中获取之前存入的 state 值
        Object expectedState = session.getAttribute("state");
        // 如果会话中 state 为空，说明回调地址的 session 与发起登录时的 session 不同，
        // 可能是因为 HTTP 和 HTTPS 混用或跨域导致 session 丢失
        if (expectedState == null) {
            String err = URLUtil.encode("当前会话中 state 为空，可能是请求地址和回调地址不一致");
            return new RedirectView("/sso/login/error?err=" + err);
        }
        // 校验回调的 state 与会话中的 state 是否匹配，防止 CSRF 攻击
        if (!expectedState.equals(state)) {
            String err = URLUtil.encode("state 参数不一致，请检查请求地址和回调地址是否一致");
            return new RedirectView("/sso/login/error?err=" + err);
        }

        // state 校验通过，使用授权码完成登录回调处理，获取前端重定向地址
        String url = ssoService.callbackHandler(provider, code);

        // 构建重定向响应，跳转到前端目标页面
        RedirectView redirect = new RedirectView();
        redirect.setUrl(url);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

}
