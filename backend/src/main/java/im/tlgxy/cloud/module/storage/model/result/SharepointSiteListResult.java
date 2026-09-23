package im.tlgxy.cloud.module.storage.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * SharePoint 站点目录列表结果类
 * <p>
 * 封装 SharePoint 站点下单个目录（List）的信息，
 * 包括目录 ID、显示名称、创建时间和 Web 访问地址。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "Sharepoint 网站 list 列表")
public class SharepointSiteListResult {

	/**
	 * 站点目录 ID，唯一标识一个目录
	 */
	@Schema(title="站点目录 id")
	private String id;

	/**
	 * 站点目录的显示名称
	 */
	@Schema(title="站点目录名称")
	private String displayName;

	/**
	 * 站点目录的创建时间
	 */
	@Schema(title="站点目录创建时间")
	private Date createdDateTime;

	/**
	 * 站点目录的 Web 访问 URL 地址
	 */
	@Schema(title="站点目录地址")
	private String webUrl;

}
