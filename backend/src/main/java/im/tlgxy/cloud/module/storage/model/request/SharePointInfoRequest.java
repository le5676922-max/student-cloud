package im.tlgxy.cloud.module.storage.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;


/**
 * SharePoint 信息请求类
 * <p>
 * 用于请求获取 SharePoint 站点的基本信息。
 * 支持国际版（Standard）和世纪互联版（China）两种类型，
 * 可通过域名前缀、站点类型和站点名称定位到具体的 SharePoint 站点。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "SharePoint 信息请求类")
public class SharePointInfoRequest {

    /**
     * SharePoint 类型：Standard（国际版、个人版等）或 China（世纪互联版）
     */
    @Schema(title = "SharePoint 类型", description ="Standard(国际版、个人版等) 或 China(世纪互联)", required = true, example = "Standard")
    private String type;

    /**
     * 访问令牌（accessToken），用于 Microsoft Graph API 的身份验证
     */
    @Schema(title = "访问令牌 (accessToken)", required = true, example = "EwBoxxxxxxxxxxxxxxxbAI=")
    private String accessToken;

    /**
     * SharePoint 站点的域名前缀
     */
    @Schema(title = "域名前缀", requiredMode = Schema.RequiredMode.REQUIRED, example = "student-cloud")
    private String domainPrefix;

    /**
     * 站点类型，如 /sites/ 或 /teams/
     */
    @Schema(title = "站点类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "/sites/")
    private String siteType;

    /**
     * SharePoint 站点名称
     */
    @Schema(title = "站点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "student-cloud")
    private String siteName;

    /**
     * 域名类型：com（国际版）或 cn（世纪互联版）
     */
    @Schema(title = "域名类型", description ="com 或 cn", example = "com")
    private String domainType;

}
