package im.tlgxy.cloud.module.storage.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SharePoint 站点信息结果类
 * <p>
 * 封装 SharePoint 站点的基本信息，
 * 包括站点 ID、显示名称和 Web 访问地址。
 * 用于搜索结果和站点列表展示。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "SharePoint 站点结果类")
public class SharepointSiteResult {

	/**
	 * SharePoint 站点 ID，唯一标识一个站点
	 */
	@Schema(title="站点 id")
	private String id;

	/**
	 * SharePoint 站点的显示名称
	 */
	@Schema(title="站点名称")
	private String displayName;

	/**
	 * SharePoint 站点的 Web 访问 URL 地址
	 */
	@Schema(title="站点地址")
	private String webUrl;

}
