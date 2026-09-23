package im.tlgxy.cloud.module.sso.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 检查 SSO 厂商名是否重复的请求体
 * <p>
 * 在新增或修改 SSO 配置时，用于向后端提交要检查的 provider 名称及当前记录 ID，
 * 以判断该 provider 是否已被其他配置占用。
 * </p>
 */
@Data
public class CheckProviderDuplicateRequest {

    /**
     * 当前记录的 ID
     * 在修改场景下传入当前正在编辑的记录 ID，检查时会排除自身，避免提示与自己重复；
     * 在新增场景下为 null，检查时不排除任何记录
     */
    @Schema(title="id")
    private Integer id;

    /**
     * 要检查的 SSO 厂商标识名
     * 即 OIDC/OAuth2 提供商简称
     */
    @Schema(title="提供商")
    private String provider;

}
