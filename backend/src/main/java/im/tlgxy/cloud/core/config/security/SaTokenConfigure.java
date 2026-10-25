package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.utils.PasswordCredentialFingerprint;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.RequestHolder;
import im.tlgxy.cloud.core.util.OnlyOfficeDownloadTokenUtils;
import im.tlgxy.cloud.core.controller.FrontIndexController;
import im.tlgxy.cloud.module.user.controller.UserController;
import im.tlgxy.cloud.module.onlyoffice.controller.OnlyOfficeController;
import im.tlgxy.cloud.module.storage.controller.proxy.ProxyDownloadController;
import jakarta.annotation.Resource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Sa-Token 权限配置类。
 * <p>
 * 负责配置 Web 拦截器，实现基于 URL 路径的权限校验。
 * 业务控制器默认要求有效登录，只有安装、认证和站点启动信息明确公开。
 * 管理员接口还会重新读取当前角色，OnlyOffice 机器请求使用独立的协议认证。
 * </p>
 * <p>
 * 注意事项：
 * </p>
 * <ul>
 *   <li>禁用了 Sa-Token 默认的路径检查功能（checkRequestPath），
 *       改为在拦截器中通过 SaRouter.match 手动指定需要校验的路径。</li>
 * </ul>
 *
 * @author zhaojun
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "GET /api/install/status", "POST /api/install",
            "GET /api/site/config/global",
            "GET /user/login/entry/validate", "GET /user/login/verify-mode",
            "GET /user/login/captcha", "GET /user/login/check",
            "GET /user/registration/config", "POST /user/registration/email-code", "POST /user/register",
            "GET /user/password-reset/config", "POST /user/password-reset/email-code", "POST /user/password-reset",
            "GET /api/sso/list");

    @Resource
    private UserService userService;

    /**
     * 注册权限校验拦截器。
     * <p>
     * 对所有请求路径添加拦截器，先验证登录，再检查管理员权限。
     * </p>
     *
     * @param registry 拦截器注册器，用于添加自定义拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
            requireAuthenticatedRequest(handle);
            // 匹配所有 /admin/** 请求（不包括 /admin 本身）
            SaRouter.match("/admin/**", () -> {
                if (isWorkspaceStorageEndpoint(RequestHolder.getRequest().getMethod(), RequestHolder.getRequest().getServletPath()))
                    requireFreshStorageAdministrator();
                else requireFreshSystemAdmin();
            });
            // These are global storage-configuration tools, not public OAuth callbacks.
            // Enforce before request-body binding or any outbound provider request.
            for (String helperPath : new String[]{"/s3/**", "/gd/**", "/sharepoint/**", "/115/**"}) {
                SaRouter.match(helperPath, this::requireFreshStorageAdministrator);
            }

        })).addPathPatterns("/**");

        // 不再依赖 SaToken 的默认路径检查功能，改为在拦截器中手动控制
        // checkRequestPath 在每次请求时校验当前路径是否在 Sa-Token 认证范围中，
        // 设为空实现表示不做任何默认检查
        SaStrategy.instance.checkRequestPath = (path, extArg1, extArg2) -> {};
    }

    /** Deny every business controller by default, including links and signed downloads. */
    private void requireAuthenticatedRequest(Object handler) {
        // Browser websockets cannot attach our ordinary token header. Only this
        // exact handler authenticates with a one-use ticket in its handshake.
        if (handler instanceof WebSocketHttpRequestHandler
                && "GET".equals(RequestHolder.getRequest().getMethod())
                && "/ws/support".equals(RequestHolder.getRequest().getServletPath())) return;
        // Frontend assets are public; unknown handler types still require authentication.
        // The existing CORS filter handles preflight requests before they reach MVC.
        if (handler instanceof ResourceHttpRequestHandler) return;
        if (!(handler instanceof HandlerMethod method)) {
            StpUtil.checkLogin();
            userService.requireLoginUser(StpUtil.getLoginIdAsInt());
            return;
        }
        HttpServletRequest request = RequestHolder.getRequest();
        String path = request.getServletPath();
        String verb = request.getMethod();

        // Let the servlet error dispatch report its original status without an authentication loop.
        if (request.getDispatcherType() == DispatcherType.ERROR && "/error".equals(path)) return;
        if (FrontIndexController.class.isAssignableFrom(method.getBeanType())
                && "/".equals(path) && ("GET".equals(verb) || "HEAD".equals(verb))) return;

        boolean protocolCallback = OnlyOfficeController.class.isAssignableFrom(method.getBeanType())
                && "callBack".equals(method.getMethod().getName())
                && "POST".equals(verb) && "/onlyOffice/callback".equals(path);
        // The callback verifies its JWT, cached document, edit permission and current owner grant.
        if (protocolCallback) return;

        boolean loginHandler = UserController.class.isAssignableFrom(method.getBeanType())
                && "doLogin".equals(method.getMethod().getName()) && "POST".equals(verb);
        boolean ssoLoginHandler = method.getBeanType().getName().equals("im.tlgxy.cloud.module.sso.controller.SsoController")
                && "GET".equals(verb)
                && ("login".equals(method.getMethod().getName()) || "callback".equals(method.getMethod().getName()))
                && path.matches("/sso/[^/]+/login(?:/callback)?");
        boolean publicEndpoint = PUBLIC_ENDPOINTS.contains(verb + " " + path) || loginHandler || ssoLoginHandler;

        if (!publicEndpoint && !StpUtil.isLogin()) {
            // Document Server cannot carry the browser's session. Its separate, expiring credential
            // authorizes only the exact proxy URL issued during authenticated preview generation.
            if (ProxyDownloadController.class.isAssignableFrom(method.getBeanType())
                    && "downAttachment".equals(method.getMethod().getName())
                    && ("GET".equals(verb) || "HEAD".equals(verb))) {
                Integer viewer = OnlyOfficeDownloadTokenUtils.authenticatedViewer(request);
                if (viewer != null) {
                    userService.requireLoginUser(viewer);
                    return;
                }
            }
            StpUtil.checkLogin();
        }
        if (StpUtil.isLogin() && !"/user/logout".equals(path)) {
            // Re-read account and workspace state even for tokens issued before a revocation.
            var freshUser = userService.requireLoginUser(StpUtil.getLoginIdAsInt());
            Object marker = StpUtil.getTokenSession().get(PasswordCredentialFingerprint.SESSION_KEY);
            if (marker != null && (!(marker instanceof String fingerprint)
                    || !PasswordCredentialFingerprint.matches(fingerprint, freshUser))) {
                StpUtil.logout();
                throw new BizException(ErrorCode.BIZ_UNAUTHORIZED.getCode(), "密码已更新，请重新登录");
            }
        }
    }

    private void requireFreshSystemAdmin() {
        StpUtil.checkLogin();
        // Do not trust a previously cached token/role: isAdmin reads the current row
        // and requires both ADMIN and enable=true on every request.
        if (!userService.isAdmin(StpUtil.getLoginIdAsInt())) {
            throw new NotRoleException("admin", StpUtil.getLoginType());
        }
    }

    static boolean isWorkspaceStorageEndpoint(String method, String path) {
        return ("GET".equals(method) && Set.of("/admin/storages", "/admin/support-storage", "/admin/storage-params",
                "/admin/storage/exist/key").contains(path))
                || ("GET".equals(method) && path.matches("/admin/storage/[0-9]+"))
                || ("POST".equals(method) && Set.of("/admin/storage", "/admin/storage/test-connection", "/admin/storage/sort", "/admin/storage/copy").contains(path))
                || ("POST".equals(method) && path.matches("/admin/storage/[0-9]+/(?:enable|disable)"))
                || ("POST".equals(method) && path.matches("/admin/storage/[0-9]+/compatibility_readme/(?:true|false)"))
                || ("DELETE".equals(method) && path.matches("/admin/storage/[0-9]+"));
    }

    private void requireFreshStorageAdministrator() {
        StpUtil.checkLogin();
        var actor = userService.requireLoginUser(StpUtil.getLoginIdAsInt());
        if (actor.getRole() != im.tlgxy.cloud.module.user.model.enums.UserRoleEnum.ADMIN
                && actor.getRole() != im.tlgxy.cloud.module.user.model.enums.UserRoleEnum.TENANT_ADMIN)
            throw new NotRoleException("storage-administrator", StpUtil.getLoginType());
    }

}
