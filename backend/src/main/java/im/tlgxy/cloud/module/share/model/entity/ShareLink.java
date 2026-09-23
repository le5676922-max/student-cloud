package im.tlgxy.cloud.module.share.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import im.tlgxy.cloud.module.share.model.enums.ShareTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 分享链接实体类
 * <p>
 * 对应数据库表 <b>share_link</b>，用于存储用户创建的分享链接的完整信息。
 * 一条记录代表一个分享，可以是单个文件、单个文件夹或多个文件/文件夹的集合。
 * </p>
 *
 * <p><b>核心字段说明：</b></p>
 * <ul>
 *   <li><b>shareKey</b> — 分享的唯一短标识，用于构建分享 URL（如 /s/{shareKey}）</li>
 *   <li><b>password</b> — 分享的访问密码，为空表示无需密码</li>
 *   <li><b>expireDate</b> — 过期时间，超过此时间后分享链接失效</li>
 *   <li><b>shareType</b> — 分享类型，区分单文件、单文件夹或多文件混合分享</li>
 *   <li><b>shareItem</b> — 以 JSON 格式存储分享的具体文件/文件夹列表</li>
 * </ul>
 *
 * <p><b>特殊字段：</b><br>
 * {@code expired} 字段标记为 {@code @TableField(exist = false)}，
 * 表示该字段不存在于数据库表中，是运行时计算得出的虚拟字段，
 * 用于在返回前端数据时标识分享是否已过期。
 * </p>
 *
 * @author zhaojun
 * @see ShareTypeEnum
 */
@Data
@Schema(title = "分享文件(夹)")
@TableName(value = "share_link")
public class ShareLink implements Serializable {

    /** 序列化版本号，保证序列化兼容性 */
    private static final long serialVersionUID = 1L;

    /**
     * 分享 ID（主键）
     * <p>
     * 数据库自增主键，唯一标识一条分享记录。
     * </p>
     */
    @Schema(title = "分享ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 分享链接的唯一标识 key
     * <p>
     * 一段随机生成的短字符串，用于构建分享访问 URL。
     * 例如：shareKey 为 "abc123"，则分享链接为 "/s/abc123"。
     * 该字段在业务上必须唯一。
     * </p>
     */
    @Schema(title = "分享链接 key")
    @TableField(value = "share_key")
    private String shareKey;

    /**
     * 分享密码
     * <p>
     * 可选的访问密码。如果设置了密码，访问者需要输入正确密码才能查看分享内容。
     * 为空或 null 表示该分享无需密码即可访问。
     * 注意：该字段在 SQL 中是保留字，因此使用反引号包裹。
     * </p>
     */
    @Schema(title = "分享密码")
    @TableField(value = "`password`")
    private String password;

    /**
     * 分享过期时间
     * <p>
     * 超过此时间后，分享链接将不可访问。
     * 为 null 表示永不过期。
     * </p>
     */
    @Schema(title = "过期时间")
    @TableField(value = "expire_date")
    private Date expireDate;

    /**
     * 存储源 key
     * <p>
     * 标识分享文件所在的存储源（如阿里云盘、OneDrive、本地存储等）。
     * 通过该 key 可以定位到具体的存储源配置。
     * </p>
     */
    @Schema(title = "存储源 key")
    @TableField(value = "storage_key")
    private String storageKey;

    /**
     * 分享所在的目录路径
     * <p>
     * 记录分享时的基础目录路径，用于定位分享文件的原始位置。
     * </p>
     */
    @Schema(title = "分享所在目录")
    @TableField(value = "share_path")
    private String sharePath;

    /**
     * 分享的具体项目内容（JSON 格式）
     * <p>
     * 以 JSON 字符串的形式存储分享中包含的文件或文件夹名称列表。
     * 例如：["file1.pdf", "folder1"]。
     * 配合 sharePath 可以完整定位每个分享条目的路径。
     * </p>
     */
    @Schema(title = "分享项目(JSON格式存储文件或文件夹名称)")
    @TableField(value = "share_item")
    private String shareItem;

    /**
     * 分享创建时间
     * <p>
     * 记录分享链接的创建时间戳。
     * </p>
     */
    @Schema(title = "创建时间")
    @TableField(value = "create_date")
    private Date createDate;

    /**
     * 分享类型
     * <p>
     * 标识该分享是单个文件、单个文件夹还是多个文件/文件夹的混合。
     * 取值为 {@link ShareTypeEnum} 中定义的枚举值。
     * </p>
     */
    @Schema(title = "分享类型", description = "FILE/FOLDER/MULTIPLE")
    @TableField(value = "share_type")
    private ShareTypeEnum shareType;

    /**
     * 创建分享的用户 ID
     * <p>
     * 关联到用户表，记录是哪个用户创建的该分享链接。
     * </p>
     */
    @Schema(title = "创建分享的用户ID")
    @TableField(value = "user_id")
    private Integer userId;

    /**
     * 下载次数
     * <p>
     * 统计通过该分享链接下载文件的总次数。
     * 每次下载操作通过 Mapper 的 incrementDownloadCount 方法原子递增。
     * </p>
     */
    @Schema(title = "下载次数")
    @TableField(value = "download_count")
    private Integer downloadCount;

    /**
     * 访问次数
     * <p>
     * 统计该分享链接被访问的总次数（包括仅查看未下载的情况）。
     * 每次访问操作通过 Mapper 的 incrementAccessCount 方法原子递增。
     * </p>
     */
    @Schema(title = "访问次数")
    @TableField(value = "access_count")
    private Integer accessCount;

    /**
     * 是否已过期（虚拟字段，不存在于数据库）
     * <p>
     * 该字段在数据库表中不存在（exist = false），由业务逻辑在运行时根据
     * {@code expireDate} 与当前时间比较后动态设置。
     * 用于前端展示分享链接是否已失效。
     * </p>
     */
    @Schema(title = "是否已过期")
    @TableField(exist = false)
    private Boolean expired;
}
