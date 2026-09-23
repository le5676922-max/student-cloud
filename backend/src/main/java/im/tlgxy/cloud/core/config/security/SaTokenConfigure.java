package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.core.util.RequestHolder;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 权限配置类。
 * <p>
 * 负责配置 Web 拦截器，实现基于 URL 路径的权限校验。
 * 核心逻辑：拦截所有路径（/**），对匹配 <b>/admin/**</b> 的请求进行权限校验，
 * 要求用户必须登录且具有 "admin" 角色才能访问管理员功能。
 * </p>
 * <p>
 * 注意事项：
 * </p>
 * <ul>
 *   <li>/admin 路径（不带后续路径）被排除在拦截之外，因为该路径是登录页面。</li>
 *   <li>禁用了 Sa-Token 默认的路径检查功能（checkRequestPath），
 *       改为在拦截器中通过 SaRouter.match 手动指定需要校验的路径。</li>
 * </ul>
 *
 * @author zhaojun
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Resource
    private UserService userService;

    /**
     * 注册权限校验拦截器。
     * <p>
     * 对所有请求路径（/**）添加 Sa-Token 的拦截器，但排除 /admin 路径（登录页面）。
     * 在拦截器内部通过 {@link SaRouter#match} 匹配 /admin/** 路径，
     * 对匹配的请求进行登录校验和角色校验。
     * </p>
     *
     * @param registry 拦截器注册器，用于添加自定义拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
            if (StpUtil.isLogin() && !"/user/logout".equals(RequestHolder.getRequest().getServletPath())) {
                userService.requireLoginUser(StpUtil.getLoginIdAsInt());
            }
            // 匹配所有 /admin/** 请求（不包括 /admin 本身）
            SaRouter.match("/admin/**", () -> {
                // 校验用户是否已登录
                StpUtil.checkLogin();
                // 校验当前用户是否具有 admin 角色
                StpUtil.checkRole("admin");
            });

        })).addPathPatterns("/**")            // 拦截所有路径
           .excludePathPatterns("/admin");     // 排除登录页面路径

        // 不再依赖 SaToken 的默认路径检查功能，改为在拦截器中手动控制
        // checkRequestPath 在每次请求时校验当前路径是否在 Sa-Token 认证范围中，
        // 设为空实现表示不做任何默认检查
        SaStrategy.instance.checkRequestPath = (path, extArg1, extArg2) -> {};
    }

}
