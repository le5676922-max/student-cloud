package im.tlgxy.cloud.module.config.model.result;

import im.tlgxy.cloud.module.config.model.dto.LinkExpireDTO;
import im.tlgxy.cloud.module.config.model.enums.FileClickModeEnum;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 前台站点全局配置响应结果类。
 * <p>
 * 用于向前台页面返回站点的所有必要配置信息，包括站点信息、显示设置、
 * 文件预览设置、品牌形象等。与 {@link im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO}
 * 不同，本类仅包含前台需要的字段，排除了敏感信息（如密码、密钥等）。
 *
 * @author zhaojun
 */
@Data
@Schema(title="全局站点设置响应类")
public class FrontSiteConfigResult {

	// ==================== 系统状态 ====================

	@Schema(title = "是否已初始化", example = "true")
	private Boolean installed;

	@Schema(title = "Debug 模式", example = "true", description ="开启 debug 模式后，可重置管理员密码")
	private Boolean debugMode;

	// ==================== 直链 ====================

	@Schema(title = "直链地址前缀", example = "true", description ="直链地址前缀, 如 http(s)://ip:port/${直链前缀}/path/filename")
	private String directLinkPrefix;

	// ==================== 站点基本信息 ====================

	@Schema(title = "站点名称", example = "TlgxyCloud Site Name")
	private String siteName;

	@Schema(title = "备案号", example = "冀ICP备12345678号-1")
	private String icp;

	// ==================== 页面布局 ====================

	@Schema(title = "页面布局", description ="full:全屏,center:居中", example = "full", requiredMode = Schema.RequiredMode.REQUIRED)
	private String layout;

	@Schema(title = "移动端页面布局", description ="full:全屏,center:居中", example = "full")
	private String mobileLayout;

	@Schema(title = "移动端显示文件大小", description = "仅适用列表视图", example = "true")
	private Boolean mobileShowSize;

	@Schema(title = "列表尺寸", description ="large:大,default:中,small:小", example = "default", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tableSize;

	// ==================== 功能开关 ====================

	@Schema(title = "是否显示生成直链功能（含直链和路径短链）", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showLinkBtn;

	@Schema(title = "是否显示生成短链功能", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showShortLink;

	@Schema(title = "是否显示生成路径链接功能", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showPathLink;

	@Schema(title = "是否显示文档区", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showDocument;

	@Schema(title = "是否显示网站公告", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showAnnouncement;

	@Schema(title = "网站公告", example = "TlgxyCloud 网站公告")
	private String announcement;

	// ==================== 自定义样式 ====================

	@Schema(title = "自定义 JS")
	private String customJs;

	@Schema(title = "自定义 CSS")
	private String customCss;

	// ==================== 自定义文件后缀 ====================

	@Schema(title = "自定义视频文件后缀格式")
	private String customVideoSuffix;

	@Schema(title = "自定义图像文件后缀格式")
	private String customImageSuffix;

	@Schema(title = "自定义音频文件后缀格式")
	private String customAudioSuffix;

	@Schema(title = "自定义文本文件后缀格式")
	private String customTextSuffix;

	@Schema(title = "自定义Office后缀格式")
	private String customOfficeSuffix;

	@Schema(title = "自定义kkFileView后缀格式")
	private String customKkFileViewSuffix;

	// ==================== 存储源 ====================

	@Schema(title = "根目录是否显示所有存储源", description ="勾选则根目录显示所有存储源列表, 反之会自动显示第一个存储源的内容.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean rootShowStorage;

	// ==================== 网络地址 ====================

	@Schema(title = "强制后端地址", description ="强制指定生成直链，短链，获取回调地址时的地址。", example = "http://xxx.example.com")
	private String forceBackendAddress;

	@Schema(title = "前端域名", description ="前端域名，前后端分离情况下需要配置.", example = "http://xxx.example.com")
	private String frontDomain;

	// ==================== 登录相关 ====================

	@Schema(title = "是否在前台显示登陆按钮", example = "true")
	private Boolean showLogin;

	@Schema(title = "登录日志模式", example = "all")
	private LoginLogModeEnum loginLogMode;

	// ==================== 文件点击行为 ====================

	@Schema(title = "默认文件点击习惯", example = "click")
	private FileClickModeEnum fileClickMode;

	@Schema(title = "移动端默认文件点击习惯", example = "click")
	private FileClickModeEnum mobileFileClickMode;

	// ==================== 上传 ====================

	@Schema(title = "最大同时上传文件数", example = "5")
	private Integer maxFileUploads;

	// ==================== 在线预览 ====================

	@Schema(title = "onlyOffice 在线预览地址", example = "https://office.example.com")
	private String onlyOfficeUrl;

	@Schema(title = "kkFileView 在线预览地址", example = "https://preview.example.com")
	private String kkFileViewUrl;

	@Schema(title = "kkFileView 预览方式", example = "iframe/newTab")
	private String kkFileViewOpenMode;

	// ==================== 文件列表 ====================

	@Schema(title = "默认最大显示文件数", example = "1000")
	private Integer maxShowSize;

	@Schema(title = "每次加载更多文件数", example = "50")
	private Integer loadMoreSize;

	@Schema(title = "默认排序字段", example = "name")
	private String defaultSortField;

	@Schema(title = "默认排序方向", example = "asc")
	private String defaultSortOrder;

	// ==================== 站点品牌 ====================

	@Schema(title = "站点 Home 名称", example = "xxx 的小站")
	private String siteHomeName;

	@Schema(title = "站点 Home Logo", example = "true")
	private String siteHomeLogo;

	@Schema(title = "站点 Logo 点击后链接", example = "https://cloud.example.com")
	private String siteHomeLogoLink;

	@Schema(title = "站点 Logo 链接打开方式", example = "_blank")
	private String siteHomeLogoTargetMode;

	// ==================== 短链 ====================

	@Schema(title = "短链过期时间设置", example = "[{value: 1, unit: \"day\"}, {value: 1, unit: \"week\"}, {value: 1, unit: \"month\"}, {value: 1, unit: \"year\"}]")
	private List<LinkExpireDTO> linkExpireTimes;

	// ==================== 密码与下载确认 ====================

	@Schema(title = "是否默认记住密码", example = "true")
	private Boolean defaultSavePwd;

	@Schema(title = "普通下载是否启用确认弹窗", example = "true")
	private Boolean enableNormalDownloadConfirm;

	@Schema(title = "打包下载是否启用确认弹窗", example = "true")
	private Boolean enablePackageDownloadConfirm;

	@Schema(title = "批量下载是否启用确认弹窗", example = "true")
	private Boolean enableBatchDownloadConfirm;

	/**
	 * 废弃的字段，不再使用悬浮菜单
	 */
	@Deprecated
	@Schema(title = "是否启用 hover 菜单", example = "true")
	private Boolean enableHoverMenu;

	// ==================== 访客状态 ====================

	/**
	 * 当前用户是否为访客（未登录用户且配置了访客首页）
	 */
	@Schema(title = "是否是游客", example = "true")
	private boolean guest;

}
