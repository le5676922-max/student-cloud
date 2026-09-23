package im.tlgxy.cloud.module.config.model.request;

import im.tlgxy.cloud.module.config.model.enums.FileClickModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 显示设置请求参数类。
 * <p>
 * 用于后台管理页面修改前台的显示相关配置，包括：
 * <ul>
 *   <li>页面布局 —— 全屏/居中布局</li>
 *   <li>列表显示 —— 列表尺寸、排序方式、每页加载数量</li>
 *   <li>文件预览 —— OnlyOffice/kkFileView 在线预览地址</li>
 *   <li>文件点击行为 —— 单击/双击打开</li>
 *   <li>自定义文件后缀 —— 各类型文件的自定义扩展名识别</li>
 *   <li>公告与文档区 —— 公告内容、文档区显隐</li>
 *   <li>下载确认弹窗 —— 普通/打包/批量下载是否弹窗确认</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "显示设置请求参数类")
public class UpdateViewSettingRequest {

	// ==================== 存储源显示 ====================

	@Schema(title = "根目录是否显示所有存储源", description ="勾选则根目录显示所有存储源列表, 反之会自动显示第一个存储源的内容.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean rootShowStorage;

	// ==================== 页面布局 ====================

	@Schema(title = "页面布局", description ="full:全屏,center:居中", example = "full", requiredMode = Schema.RequiredMode.REQUIRED)
	private String layout;

	@Schema(title = "移动端页面布局", description ="full:全屏,center:居中", example = "full")
	private String mobileLayout;

	@Schema(title = "移动端显示文件大小", description = "仅适用列表视图", example = "true")
	private Boolean mobileShowSize;

	// ==================== 列表显示 ====================

	@Schema(title = "列表尺寸", description ="large:大,default:中,small:小", example = "default", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tableSize;

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

	// ==================== 公告与文档区 ====================

	@Schema(title = "是否显示文档区", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showDocument;

	@Schema(title = "是否显示网站公告", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	private Boolean showAnnouncement;

	@Schema(title = "网站公告", example = "TlgxyCloud 网站公告")
	private String announcement;

	// ==================== 自定义样式 ====================

	@Schema(title = "自定义 CSS")
	private String customCss;

	@Schema(title = "自定义 JS")
	private String customJs;

	// ==================== 文件点击行为 ====================

	@Schema(title = "默认文件点击习惯", example = "click")
	private FileClickModeEnum fileClickMode;

	@Schema(title = "移动端默认文件点击习惯", example = "click")
	private FileClickModeEnum mobileFileClickMode;

	// ==================== 在线预览 ====================

	@Schema(title = "onlyOffice 在线预览地址", example = "https://office.example.com")
	private String onlyOfficeUrl;

	@Schema(title = "onlyOffice Secret", example = "X9rBGypwWE86Lca8e4Mo55iHFoiyh9ed")
	private String onlyOfficeSecret;

	@Schema(title = "kkFileView 在线预览地址", example = "https://preview.example.com")
	private String kkFileViewUrl;

	@Schema(title = "kkFileView 预览方式", example = "iframe/newTab")
	private String kkFileViewOpenMode;

	// ==================== 文件列表分页 ====================

	@Schema(title = "默认最大显示文件数", example = "1000")
	private Integer maxShowSize;

	@Schema(title = "每次加载更多文件数", example = "50")
	private Integer loadMoreSize;

	@Schema(title = "默认排序字段", example = "name")
	private String defaultSortField;

	@Schema(title = "默认排序方向", example = "asc")
	private String defaultSortOrder;

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

}
