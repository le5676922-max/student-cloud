package im.tlgxy.cloud.module.storage.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 存储源基本属性实体类
 * <p>
 * 对应数据库表 storage_source，是系统的核心实体之一。
 * 每个存储源代表一个可被 TlgxyCloud 管理和访问的文件存储后端，
 * 支持本地存储、阿里云 OSS、OneDrive、FTP/SFTP、WebDAV 等多种存储类型。
 * </p>
 * <p>
 * 包含存储源的基本配置信息：名称、类型、是否启用、缓存配置、搜索配置等。
 * 具体的连接参数（如 accessKey、secretKey、bucketName 等）存储在
 * {@link StorageSourceConfig} 表中，通过 storageId 外键关联。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "存储源基本属性")
@TableName(value = "storage_source")
public class StorageSource implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(title = "ID, 新增无需填写", example = "1")
    private Integer id;

    /** 是否启用该存储源，禁用的存储源在前端不可见 */
    @TableField(value = "`enable`")
    @Schema(title = "是否启用", example = "true")
    private Boolean enable;

    /**
     * 是否启用文件操作功能（上传、编辑、删除等）
     * @deprecated 已废弃，改用权限管理体系
     */
    @TableField(value = "`enable_file_operator`")
    @Schema(title = "是否启用文件操作功能", example = "true", description ="是否启用文件上传，编辑，删除等操作.")
    @Deprecated
    private Boolean enableFileOperator;

    /**
     * 是否允许匿名用户进行文件操作
     * @deprecated 已废弃，改用权限管理体系
     */
    @TableField(value = "`enable_file_anno_operator`")
    @Schema(title = "是否允许匿名进行文件操作", example = "true", description ="是否允许匿名进行文件上传，编辑，删除等操作.")
    @Deprecated
    private Boolean enableFileAnnoOperator;

    /** 是否开启文件列表缓存，缓存可以大幅提升大目录的浏览速度 */
    @TableField(value = "`enable_cache`")
    @Schema(title = "是否开启缓存", example = "true")
    private Boolean enableCache;

    /** 存储源的显示名称 */
    @TableField(value = "`name`")
    @Schema(title = "存储源名称", example = "阿里云 OSS 存储")
    private String name;

    /**
     * 存储源别名，用作 URL 访问标识
     * 例如：http://ip:port/{key} 中的 key 部分
     */
    @TableField(value = "`key`")
    @Schema(title = "存储源别名", example = "存储源别名，用于 URL 中展示, 如 http://ip:port/{存储源别名}")
    private String key;

    /** 存储源备注信息，仅后台管理员可见，用于区分多个存储源 */
    @TableField(value = "`remark`")
    @Schema(title = "存储源备注", example = "这是一个备注信息, 用于管理员区分不同的存储源, 此字段仅管理员可见")
    private String remark;

    /** 是否开启缓存自动刷新 */
    @TableField(value = "auto_refresh_cache")
    @Schema(title = "是否开启缓存自动刷新", example = "true")
    private Boolean autoRefreshCache;

    /** 存储源类型（本地存储、阿里云 OSS、OneDrive 等） */
    @TableField(value = "`type`")
    @Schema(title = "存储源类型")
    private StorageTypeEnum type;

    /** 是否启用文件搜索功能 */
    @TableField(value = "search_enable")
    @Schema(title = "是否开启搜索", example = "true")
    private Boolean searchEnable;

    /** 搜索时是否忽略文件名大小写 */
    @TableField(value = "search_ignore_case")
    @Schema(title = "搜索是否忽略大小写", example = "true")
    private Boolean searchIgnoreCase;

    /** 搜索模式：仅从缓存搜索 或 直接全量搜索 */
    @TableField(value = "`search_mode`")
    @Schema(title = "搜索模式", example = "SEARCH_CACHE", description ="仅从缓存中搜索或直接全量搜索")
    private SearchModeEnum searchMode;

    /** 排序值，值越小越靠前显示 */
    @TableField(value = "order_num")
    @Schema(title = "排序值", example = "1")
    private Integer orderNum;

    /** 是否默认以图片模式展示文件列表（缩略图模式） */
    @TableField(value = "default_switch_to_img_mode")
    @Schema(title = "是否默认开启图片模式", example = "true")
    private Boolean defaultSwitchToImgMode;

    /** 是否兼容 README 模式，在进入目录时自动读取并显示 readme.md 文件内容 */
    @TableField(value = "compatibility_readme")
    @Schema(title = "兼容 readme 模式", example = "true", description ="兼容模式, 目录文档读取 readme.md 文件")
    private Boolean compatibilityReadme;

    @TableField(value = "tenant_id")
    private Integer tenantId;

    @TableField(value = "owner_user_id")
    private Integer ownerUserId;

}
