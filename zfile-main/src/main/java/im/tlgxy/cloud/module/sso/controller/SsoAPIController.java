package im.tlgxy.cloud.module.sso.controller;

import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.sso.model.response.SsoLoginItemResponse;
import im.tlgxy.cloud.module.sso.service.SsoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 单点登录公开 API 控制器
 * <p>
 * 提供前端登录页面所需的 SSO（单点登录）相关接口。
 * 与 {@link SsoController}（处理登录跳转和回调）和 {@link SsoManagerController}（后台管理）不同，
 * 本控制器仅负责暴露给未登录用户查看的 SSO 服务商列表数据，
 * 用于在登录页面上动态展示可用的第三方登录方式（如 GitHub、Google、OIDC 等）。
 * </p>
 *
 * @author OnEvent
 */
@Slf4j
@Tag(name = "单点登录接口")
@RestController
@RequestMapping("/api/sso")
@RequiredArgsConstructor
public class SsoAPIController {

    /**
     * SSO 服务层，封装了单点登录的核心业务逻辑
     */
    private final SsoService ssoService;

    /**
     * 获取登录页面可用的 SSO 服务商列表
     * <p>
     * 该接口无需登录即可访问，返回当前系统中已配置并启用的所有 SSO 服务商信息，
     * 包括服务商名称、图标、登录跳转地址等，供前端登录页面渲染第三方登录按钮。
     * </p>
     *
     * @return AjaxJson 对象，data 字段为 SSO 服务商登录项列表
     */
    @GetMapping("/list")
    @Operation(summary = "登录页面 SSO 服务商列表")
    public AjaxJson<List<SsoLoginItemResponse>> list() {
        // 调用服务层获取所有可用的 SSO 登录项，封装为成功响应返回
        return AjaxJson.getSuccessData(ssoService.listAllLoginItems());
    }

}
