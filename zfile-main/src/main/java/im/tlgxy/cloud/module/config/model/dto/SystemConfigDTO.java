package im.tlgxy.cloud.module.config.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import im.tlgxy.cloud.module.config.annotation.JSONStringParse;
import im.tlgxy.cloud.module.config.model.enums.FileClickModeEnum;
import im.tlgxy.cloud.module.link.model.enums.RefererTypeEnum;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import im.tlgxy.cloud.module.user.model.enums.LoginVerifyModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统设置数据传输对象（DTO），用于在前后端之间传递系统配置数据。
 * <p>
 * 包含了站点所有可配置项，涵盖站点信息、显示设置、安全设置、直链设置、
 * WebDAV 设置、文件预览设置、访问控制设置等多个维度。
 * <p>
 * 部分字段标注了 {@link Deprecated}，表示已被新字段替代，仅保留用于向下兼容。
 * 带有 {@link JSONStringParse} 注解的字段在序列化时会按 JSON 字符串格式解析。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "系统设置类")
public class SystemConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 站点基本信息 ====================

    @Schema(title = "站点名称", example = "TlgxyCloud Site Name")
    private String siteName;

    @Schema(title = "用户名", example = "admin")
    @Deprecated
    private String username;

    @Schema(title = "头像地址", example = "https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png")
    private String avatar;

    @Schema(title = "备案号", example = "冀ICP备12345678号-1")
    private String icp;

    /**
     * 密码字段，序列化时忽略（防止泄露），已废弃
     */
    @JsonIgnore
    @Deprecated
    private String password;

    // ==================== 自定义样式 ====================

    @Schema(title = "自定义 JS")
    private String customJs;

    @Schema(title = "自定义 CSS")
    private String customCss;

    // ==================== 显示设置 ====================

    @Schema(title = "列表尺寸", description ="large:大,default:中,small:小", example = "default")
    private String tableSize;

    @Schema(title = "是否显示文档区", example = "true")
    private Boolean showDocument;

    @Schema(title = "网站公告", example = "TlgxyCloud 网站公告")
    private String announcement;

    @Schema(title = "是否显示网站公告", example = "true")
    private Boolean showAnnouncement;

    @Schema(title = "页面布局", description ="full:全屏,center:居中", example = "full")
    private String layout;

    @Schema(title = "移动端页面布局", description ="full:全屏,center:居中", example = "full")
    private String mobileLayout;

    @Schema(title = "移动端显示文件大小", description = "仅适用列表视图", example = "true")
    private Boolean mobileShowSize;

    // ==================== 链接按钮显示 ====================

    @Schema(title = "是否显示生成直链功能（含直链和路径短链）", example = "true")
    private Boolean showLinkBtn;

    @Schema(title = "是否显示生成短链功能", example = "true")
    private Boolean showShortLink;

    @Schema(title = "是否显示生成路径链接功能", example = "true")
    private Boolean showPathLink;

    // ==================== 系统状态 ====================

    @Schema(title = "是否已初始化", example = "true")
    private Boolean installed;

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

    // ==================== 直链设置 ====================

    @Schema(title = "直链地址前缀")
    private String directLinkPrefix;

    @Schema(title = "直链 Referer 防盗链类型")
    private RefererTypeEnum refererType;

    @Schema(title = "是否记录下载日志", example = "true")
    private Boolean recordDownloadLog;

    @Schema(title = "直链 Referer 是否允许为空")
    private Boolean refererAllowEmpty;

    @Schema(title = "直链 Referer 值")
    private String refererValue;

    // ==================== 登录安全 ====================

    /**
     * 废弃的字段，改为使用 {@link #adminTwoFactorVerify} 和 {@link #loginVerifySecret} 代替
     */
    @Schema(title = "管理员登陆验证方式，目前仅支持 2FA 认证或关闭")
    @Deprecated
    private LoginVerifyModeEnum loginVerifyMode;

    @Schema(title = "登陆验证 Secret")
    private String loginVerifySecret;

    @Schema(title = "是否启用登陆验证码", example = "true")
    private Boolean loginImgVerify;

    @Schema(title = "是否为管理员启用双因素认证", example = "true")
    private Boolean adminTwoFactorVerify;

    // ==================== 存储源显示 ====================

    @Schema(title = "根目录是否显示所有存储源", description ="勾选则根目录显示所有存储源列表, 反之会自动显示第一个存储源的内容.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean rootShowStorage;

    // ==================== 网络地址 ====================

    @Schema(title = "强制后端地址", description ="强制指定生成直链，短链，获取回调地址时的地址。", example = "http://xxx.example.com")
    private String forceBackendAddress;

    @Schema(title = "前端域名", description ="前端域名，前后端分离情况下需要配置.", example = "http://xxx.example.com")
    private String frontDomain;

    // ==================== 前台登录 ====================

    @Schema(title = "是否在前台显示登陆按钮", example = "true")
    private Boolean showLogin;

    @Schema(title = "安全登录入口", description = "用于隐藏默认登录地址的安全入口，不包含 '/'", example = "admin")
    private String secureLoginEntry;

    @Schema(title = "登录日志模式", example = "all")
    private LoginLogModeEnum loginLogMode;

    // ==================== 加密相关 ====================

    @Schema(title = "RAS Hex Key", example = "r2HKbzc1DfvOs5uHhLn7pA==")
    private String rsaHexKey;

    // ==================== 文件点击行为 ====================

    @Schema(title = "默认文件点击习惯", example = "click")
    private FileClickModeEnum fileClickMode;

    @Schema(title = "移动端默认文件点击习惯", example = "click")
    private FileClickModeEnum mobileFileClickMode;

    // ==================== 授权 ====================

    @Schema(title = "授权码", example = "e619510f-cdcd-f657-6c5e-2d12e9a28ae5")
    private String authCode;

    // ==================== 上传设置 ====================

    @Schema(title = "最大同时上传文件数", example = "5")
    private Integer maxFileUploads;

    // ==================== 在线预览 ====================

    @Schema(title = "onlyOffice 在线预览地址", example = "https://office.example.com")
    private String onlyOfficeUrl;

    @Schema(title = "onlyOffice Secret", example = "X9rBGypwWE86Lca8e4Mo55iHFoiyh9ed")
    private String onlyOfficeSecret;

    @Schema(title = "kkFileView 在线预览地址", example = "https://preview.example.com")
    private String kkFileViewUrl;

    @Schema(title = "kkFileView 预览方式", example = "iframe/newTab")
    private String kkFileViewOpenMode;

    // ==================== WebDAV 设置 ====================

    @Schema(title = "启用 WebDAV", example = "true")
    private Boolean webdavEnable;

    @Schema(title = "WebDAV 服务器中转下载", example = "true")
    private Boolean webdavProxy;

    @Schema(title = "WebDAV 匿名用户访问", example = "true")
    private Boolean webdavAllowAnonymous;

    @Schema(title = "WebDAV 账号", example = "admin")
    private String webdavUsername;

    @Schema(title = "WebDAV 密码", example = "123456")
    private String webdavPassword;

    // ==================== 访问控制 ====================

    @Schema(title = "是否允许路径直链可直接访问", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean allowPathLinkAnonAccess;

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

    @Schema(title = "管理员页面点击 Logo 回到首页打开方式", example = "_blank")
    private String siteAdminLogoTargetMode;

    @Schema(title = "管理员页面点击版本号打开更新日志", example = "true")
    private Boolean siteAdminVersionOpenChangeLog;

    // ==================== 直链限制 ====================

    @Schema(title = "限制直链下载秒数", example = "_blank")
    private Integer linkLimitSecond;

    @Schema(title = "限制直链下载次数", example = "_blank")
    private Integer linkDownloadLimit;

    // ==================== 其他 ====================

    @Schema(title = "网站 favicon 图标地址", example = "https://www.example.com/favicon.ico")
    private String faviconUrl;

    @Schema(title = "短链过期时间设置", example = "[{value: 1, unit: \"day\"}, {value: 1, unit: \"week\"}, {value: 1, unit: \"month\"}, {value: 1, unit: \"year\"}]")
    @JSONStringParse
    private List<LinkExpireDTO> linkExpireTimes;

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

    // ==================== 黑名单 ====================

    @Schema(title = "访问 ip 黑名单", example = "162.13.1.0/24\n192.168.1.1")
    private String accessIpBlocklist;

    @Schema(title = "访问 ua 黑名单", example = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36*")
    private String accessUaBlocklist;

    // ==================== 访客首页 ====================

    @Schema(title = "匿名用户首页显示内容")
    private String guestIndexHtml;

    /**
     * 获取公告内容，为空时返回空字符串。
     *
     * @return 公告内容字符串
     */
    public String getAnnouncement() {
        return announcement == null ? "" : announcement;
    }

    /**
     * 获取短链过期时间配置列表，为空时返回默认值（1 天）。
     *
     * @return 链接过期时间 DTO 列表
     */
    public List<LinkExpireDTO> getLinkExpireTimes() {
        if (linkExpireTimes == null) {
            // 默认值：1 天
            LinkExpireDTO linkExpireDTO = new LinkExpireDTO();
            linkExpireDTO.setValue(1);
            linkExpireDTO.setUnit("d");
            linkExpireDTO.setSeconds(86400L);
            linkExpireTimes = new ArrayList<>();
            linkExpireTimes.add(linkExpireDTO);
        }
        return linkExpireTimes;
    }

    /**
     * 获取页面布局设置，为空时默认返回 "full"（全屏布局）。
     *
     * @return 布局模式字符串
     */
    public String getLayout() {
        return layout == null ? "full" : layout;
    }

    /**
     * 获取移动端页面布局设置，为空时使用 PC 端布局设置。
     *
     * @return 移动端布局模式字符串
     */
    public String getMobileLayout() {
        return mobileLayout == null ? getLayout() : mobileLayout;
    }

    /**
     * 获取普通下载是否启用确认弹窗配置.
     *
     * @return  若为空则返回 true（默认启用）
     */
    public Boolean getEnableNormalDownloadConfirm() {
        return enableNormalDownloadConfirm == null ? Boolean.TRUE : enableNormalDownloadConfirm;
    }

    /**
     * 获取打包下载是否启用确认弹窗配置.
     *
     * @return  若为空则返回 true（默认启用）
     */
    public Boolean getEnablePackageDownloadConfirm() {
        return enablePackageDownloadConfirm == null ? Boolean.TRUE : enablePackageDownloadConfirm;
    }

    /**
     * 获取批量下载是否启用确认弹窗配置.
     *
     * @return  若为空则返回 true（默认启用）
     */
    public Boolean getEnableBatchDownloadConfirm() {
        return enableBatchDownloadConfirm == null ? Boolean.TRUE : enableBatchDownloadConfirm;
    }
}
