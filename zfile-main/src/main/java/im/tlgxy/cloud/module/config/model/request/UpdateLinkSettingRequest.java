package im.tlgxy.cloud.module.config.model.request;

import im.tlgxy.cloud.module.config.model.dto.LinkExpireDTO;
import im.tlgxy.cloud.module.link.model.enums.RefererTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 直链设置请求参数类。
 * <p>
 * 用于后台管理页面修改直链相关的配置，包括：
 * <ul>
 *   <li>直链前缀 —— 直链 URL 的前缀路径</li>
 *   <li>Referer 防盗链 —— 限制直链只能从指定来源访问</li>
 *   <li>链接有效期 —— 短链和直链的过期时间预设选项</li>
 *   <li>下载限制 —— 限速、限次下载</li>
 *   <li>功能开关 —— 是否显示直链、短链、路径链接按钮</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "直链设置请求参数类")
public class UpdateLinkSettingRequest {

	/**
	 * 是否记录每次直链下载的日志
	 */
	@Schema(title = "是否记录下载日志", example = "true")
	private Boolean recordDownloadLog;

	/**
	 * 直链 Referer 防盗链类型（如：无限制、白名单、黑名单等）
	 */
	@Schema(title = "直链 Referer 防盗链类型")
	private RefererTypeEnum refererType;

	/**
	 * 直链 Referer 是否允许为空（即允许浏览器直接访问）
	 */
	@Schema(title = "直链 Referer 是否允许为空")
	private Boolean refererAllowEmpty;

	/**
	 * 直链 Referer 值（白名单或黑名单的具体值）
	 */
	@Schema(title = "直链 Referer 值")
	private String refererValue;

	/**
	 * 直链地址前缀，直链 URL 的前缀路径（必填）
	 */
	@Schema(title = "直链地址前缀")
	@NotBlank(message = "直链地址前缀不能为空")
	private String directLinkPrefix;

	/**
	 * 是否在前台显示生成直链按钮（包含直链和路径短链）
	 */
	@Schema(title = "是否显示生成直链功能（含直链和路径短链）", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showLinkBtn;

	/**
	 * 是否在前台显示生成短链按钮
	 */
	@Schema(title = "是否显示生成短链功能", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showShortLink;

	/**
	 * 是否在前台显示生成路径链接按钮
	 */
	@Schema(title = "是否显示生成路径链接功能", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showPathLink;

	/**
	 * 是否允许匿名用户直接通过路径直链访问文件
	 */
	@Schema(title = "是否允许路径直链可直接访问", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean allowPathLinkAnonAccess;

	/**
	 * 直链下载限速（单位：秒），在此时间内限制下载
	 */
	@Schema(title = "限制直链下载秒数", example = "_blank")
	private Integer linkLimitSecond;

	/**
	 * 直链下载次数限制，超过此次数后链接失效
	 */
	@Schema(title = "限制直链下载次数", example = "_blank")
	private Integer linkDownloadLimit;

	/**
	 * 短链过期时间预设选项，用户创建短链时可选择这些预设的过期时间
	 */
	@Schema(title = "短链过期时间设置", example = "[{value: 1, unit: \"day\"}, {value: 1, unit: \"week\"}, {value: 1, unit: \"month\"}, {value: 1, unit: \"year\"}]")
	private List<LinkExpireDTO> linkExpireTimes;

}
