package im.tlgxy.cloud.module.sso.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SSO 登录项响应体
 * <p>
 * 用于前端登录页展示可用的 SSO 登录入口。
 * 仅包含展示所必需的字段（provider、name、icon），
 * 不包含敏感信息（如 client_secret、token_url 等）。
 * </p>
 */
@Data
public class SsoLoginItemResponse {

    /**
     * OIDC/OAuth2 厂商标识名
     * 作为唯一标识，用于前端路由到对应的 SSO 登录流程
     */
    @Schema(title = "OIDC/OAuth2 厂商名", example = "Logto", description = "简称，仅可包含数字、字母，-，_")
    private String provider;

    /**
     * SSO 厂商的显示名称
     * 在前端登录页面，用户鼠标悬浮到图标上时显示的文本
     */
    @Schema(title = "显示名称", description = "登录页悬浮到图标上的名称")
    private String name;

    /**
     * SSO 厂商的图标
     * 在登录页面显示的图标，支持 URL 链接、SVG 代码或 Base64 编码格式
     */
    @Schema(title = "ICON", description = "登录页显示的图标，支持 URL、SVG、Base64 格式")
    private String icon;

}
