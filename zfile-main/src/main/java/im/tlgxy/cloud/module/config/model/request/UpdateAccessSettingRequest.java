package im.tlgxy.cloud.module.config.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 站点访问控制设置请求参数类。
 * <p>
 * 用于后台管理页面修改站点的访问控制策略，包括 IP 黑名单和 User-Agent 黑名单。
 * 支持多行输入，每行一条规则，支持 CIDR 格式的 IP 段和通配符匹配的 UA。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "站点访问控制参数类")
public class UpdateAccessSettingRequest {

    /**
     * 访问 IP 黑名单，支持多行输入，每行一个 IP 或 CIDR 段（如 162.13.1.0/24）
     */
    @Schema(title = "访问 ip 黑名单", example = "162.13.1.0/24\n192.168.1.1")
    private String accessIpBlocklist;

    /**
     * 访问 User-Agent 黑名单，支持多行输入，每行一个 UA 规则（支持通配符 *）
     */
    @Schema(title = "访问 ua 黑名单", example = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36*")
    private String accessUaBlocklist;

}
