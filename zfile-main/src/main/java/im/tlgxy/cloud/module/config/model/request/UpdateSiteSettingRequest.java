package im.tlgxy.cloud.module.config.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 站点基本设置请求参数类。
 * <p>
 * 用于后台管理页面修改站点的基本信息，包括：
 * <ul>
 *   <li>站点名称 —— 前台展示的网站名称（必填）</li>
 *   <li>网络地址 —— 强制后端地址和前端域名</li>
 *   <li>品牌形象 —— 头像、Logo、favicon 等</li>
 *   <li>备案信息 —— ICP 备案号</li>
 *   <li>授权与上传 —— 授权码和最大上传文件数</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "站点设置请求参数类")
public class UpdateSiteSettingRequest {

	/**
	 * 站点名称，前台展示的网站标题（必填）
	 */
	@Schema(title = "站点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "TlgxyCloud Site Name")
	@NotBlank(message = "站点名称不能为空")
	private String siteName;

	/**
	 * 强制后端地址，生成直链/短链/回调地址时使用的后端地址
	 */
	@Schema(title = "强制后端地址", description ="强制指定生成直链，短链，获取回调地址时的地址。", example = "http://xxx.example.com")
	private String forceBackendAddress;

	/**
	 * 前端域名，前后端分离部署时需要配置
	 */
	@Schema(title = "前端域名", description ="前端域名，前后端分离情况下需要配置.", example = "http://xxx.example.com")
	private String frontDomain;

	/**
	 * 站点头像图片 URL
	 */
	@Schema(title = "头像地址", example = "https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png")
	private String avatar;

	/**
	 * ICP 备案号
	 */
	@Schema(title = "备案号", example = "冀ICP备12345678号-1")
	private String icp;

	/**
	 * 系统授权码
	 */
	@Schema(title = "授权码", example = "e619510f-cdcd-f657-6c5e-2d12e9a28ae5")
	private String authCode;

	/**
	 * 最大同时上传文件数
	 */
	@Schema(title = "最大同时上传文件数", example = "5")
	private Integer maxFileUploads;

	/**
	 * 站点首页名称（如 "xxx 的小站"）
	 */
	@Schema(title = "站点 Home 名称", example = "xxx 的小站")
	private String siteHomeName;

	/**
	 * 站点首页 Logo 图片
	 */
	@Schema(title = "站点 Home Logo", example = "true")
	private String siteHomeLogo;

	/**
	 * 站点 Logo 点击后的跳转链接
	 */
	@Schema(title = "站点 Logo 点击后链接", example = "https://cloud.example.com")
	private String siteHomeLogoLink;

	/**
	 * 站点 Logo 链接的打开方式（如 _blank 新窗口打开）
	 */
	@Schema(title = "站点 Logo 链接打开方式", example = "_blank")
	private String siteHomeLogoTargetMode;

	/**
	 * 网站 favicon 图标地址
	 */
	@Schema(title = "网站 favicon 图标地址", example = "https://www.example.com/favicon.ico")
	private String faviconUrl;

	/**
	 * 管理员页面点击 Logo 回到首页的打开方式
	 */
	@Schema(title = "管理员页面点击 Logo 回到首页打开方式", example = "_blank")
	private String siteAdminLogoTargetMode;

	/**
	 * 管理员页面点击版本号是否打开更新日志
	 */
	@Schema(title = "管理员页面点击版本号打开更新日志", example = "true")
	private Boolean siteAdminVersionOpenChangeLog;

}
