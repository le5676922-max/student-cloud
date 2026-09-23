package im.tlgxy.cloud.module.storage.model.result;

import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.bo.RefreshTokenCacheBO;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 存储源后台管理响应结果类
 * <p>
 * 封装后台管理页面中存储源的完整配置信息。
 * 包含了存储源的所有可管理属性，如启用状态、文件操作权限、
 * 缓存策略、搜索设置、排序值等。还包含缓存的刷新令牌信息，
 * 用于显示 OAuth2 认证的刷新状态。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "存储源设置后台管理 Result")
public class StorageSourceAdminResult {

    /**
     * 存储源 ID，唯一标识
     */
	@Schema(title = "ID, 新增无需填写", example = "1")
	private Integer id;


    /**
     * 是否启用该存储源
     */
	@Schema(title = "是否启用", example = "true")
	private Boolean enable;


    /**
     * 是否启用文件操作功能（上传、编辑、删除等）
     */
	@Schema(title = "是否启用文件操作功能", example = "true", description ="是否启用文件上传，编辑，删除等操作.")
	private Boolean enableFileOperator;


    /**
     * 是否允许匿名用户进行文件操作
     */
	@Schema(title = "是否允许匿名进行文件操作", example = "true", description ="是否允许匿名进行文件上传，编辑，删除等操作.")
	private Boolean enableFileAnnoOperator;

    /**
     * 是否开启文件列表缓存
     */
	@Schema(title = "是否开启缓存", example = "true")
	private Boolean enableCache;


    /**
     * 存储源名称，用于显示
     */
	@Schema(title = "存储源名称", example = "阿里云 OSS 存储")
	private String name;


    /**
     * 存储源别名（key），用于 URL 中展示
     */
	@Schema(title = "存储源别名", example = "存储源别名，用于 URL 中展示, 如 http://ip:port/{存储源别名}")
	private String key;


    /**
     * 存储源备注信息，仅管理员可见
     */
	@Schema(title = "存储源备注", example = "这是一个备注信息, 用于管理员区分不同的存储源, 此字段仅管理员可见")
	private String remark;


    /**
     * 是否开启缓存自动刷新
     */
	@Schema(title = "是否开启缓存自动刷新", example = "true")
	private Boolean autoRefreshCache;


    /**
     * 存储源类型，如 FTP、S3、OneDrive 等
     */
	@Schema(title = "存储源类型")
	private StorageTypeEnum type;


    /**
     * 是否开启文件搜索功能
     */
	@Schema(title = "是否开启搜索", example = "true")
	private Boolean searchEnable;


    /**
     * 搜索时是否忽略大小写
     */
	@Schema(title = "搜索是否忽略大小写", example = "true")
	private Boolean searchIgnoreCase;


    /**
     * 搜索模式：仅从缓存中搜索或直接全量搜索
     */
	@Schema(title = "搜索模式", example = "SEARCH_CACHE", description ="仅从缓存中搜索或直接全量搜索")
	private SearchModeEnum searchMode;


    /**
     * 排序值，用于控制存储源在列表中的显示顺序
     */
	@Schema(title = "排序值", example = "1")
	private Integer orderNum;


    /**
     * 是否默认开启图片模式（缩略图浏览模式）
     */
	@Schema(title = "是否默认开启图片模式", example = "true")
	private Boolean defaultSwitchToImgMode;


    /**
     * 存储源刷新令牌信息，用于展示 OAuth2 认证的刷新状态
     */
	@Schema(title = "存储源刷新信息")
	private RefreshTokenCacheBO.RefreshTokenInfo refreshTokenInfo;


    /**
     * 是否兼容 readme 模式，开启后会在目录中自动读取 readme.md 文件
     */
	@Schema(title = "兼容 readme 模式", example = "true", description ="兼容模式, 目录文档读取 readme.md 文件")
	private Boolean compatibilityReadme;

    private Integer tenantId;

    private Integer ownerUserId;

}
