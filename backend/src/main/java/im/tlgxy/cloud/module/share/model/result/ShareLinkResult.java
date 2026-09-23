package im.tlgxy.cloud.module.share.model.result;

import com.fasterxml.jackson.annotation.JsonFormat;
import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.enums.ShareTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 分享链接详情响应结果类
 * <p>
 * 当查询某个分享链接的详细信息时，后端返回此对象。包含分享链接的完整元数据：
 * 基本信息（key、密码状态、过期状态）、存储源信息、分享条目列表、分享类型、
 * 时间信息（过期时间、创建时间）、统计信息（访问次数、下载次数）以及
 * 创建者信息（用户 ID、用户名、昵称）。
 * </p>
 * <p>
 * 注意：password 字段仅在特定场景（如管理员查看或创建者自己查看）下才会返回真实值，
 * 对外部访问者始终返回 null，以保护分享密码安全。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "分享链接详情响应")
public class ShareLinkResult {

    /**
     * 分享链接的唯一标识 key
     * <p>
     * 用于在 URL 中标识分享链接，如 "/s/abc12345"。
     * </p>
     */
    @Schema(title = "分享链接 key")
    private String shareKey;

    /**
     * 该分享链接是否需要密码才能访问
     * <p>
     * 由服务端根据分享链接是否设置了密码自动计算得出，而非直接取自数据库字段。
     * true 表示需要输入密码，false 表示无需密码即可访问。
     * </p>
     */
    @Schema(title = "是否需要密码")
    private Boolean needPassword;

    /**
     * 该分享链接是否已过期
     * <p>
     * 由服务端根据当前时间与 expireDate 比较自动计算得出。
     * true 表示已过期不可访问，false 表示仍在有效期内。
     * </p>
     */
    @Schema(title = "是否已过期")
    private Boolean expired;

    /**
     * 分享链接的过期时间
     * <p>
     * 超过此时间后分享链接将不可访问。如果为 null，表示永不过期。
     * 序列化为 JSON 时使用东八区时间格式 "yyyy-MM-dd HH:mm:ss"。
     * </p>
     */
    @Schema(title = "过期时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date expireDate;

    /**
     * 分享内容所在的存储源 key
     * <p>
     * 存储源的唯一标识，用于在 StorageSourceContext 中查找对应的文件服务实例。
     * </p>
     */
    @Schema(title = "存储源 key")
    private String storageKey;

    /**
     * 分享内容所在的存储源数据库 ID
     * <p>
     * 由服务端根据 storageKey 查询 StorageSource 表后填充。
     * </p>
     */
    @Schema(title = "存储源 ID")
    private Integer storageId;

    /**
     * 分享内容所在的存储源名称
     * <p>
     * 友好的存储源显示名称，由服务端根据 storageKey 查询后填充，方便前端展示。
     * </p>
     */
    @Schema(title = "存储源名称")
    private String storageName;

    /**
     * 分享内容所在的绝对路径
     * <p>
     * 在创建分享时，将用户的基础路径与分享路径拼接为绝对路径存储，
     * 以避免后续用户基础路径变更导致分享链接失效。
     * </p>
     */
    @Schema(title = "分享所在目录")
    private String sharePath;

    /**
     * 分享条目列表
     * <p>
     * 当分享类型为"指定条目分享"时，此列表指定了分享范围内包含的文件和文件夹。
     * 为空列表时表示分享整个目录下的所有内容。
     * 从数据库中的 shareItem JSON 字符串解析而来。
     * </p>
     */
    @Schema(title = "分享条目列表")
    private List<ShareEntryDTO> shareEntries;

    /**
     * 分享类型枚举
     * <p>
     * 区分不同的分享模式，如：分享整个目录、分享指定条目等。
     * </p>
     */
    @Schema(title = "分享类型")
    private ShareTypeEnum shareType;

    /**
     * 分享链接的创建时间
     * <p>
     * 序列化为 JSON 时使用东八区时间格式 "yyyy-MM-dd HH:mm:ss"。
     * </p>
     */
    @Schema(title = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createDate;

    /**
     * 分享链接的累计访问次数
     * <p>
     * 每次有人访问该分享链接的根目录时递增。
     * 使用内存缓冲区 + 定时刷新的方式更新数据库，以减轻数据库压力。
     * </p>
     */
    @Schema(title = "访问次数")
    private Integer accessCount;

    /**
     * 分享链接的累计下载次数
     * <p>
     * 每次有人通过该分享链接下载文件时递增。
     * 使用内存缓冲区 + 定时刷新的方式更新数据库，以减轻数据库压力。
     * </p>
     */
    @Schema(title = "下载次数")
    private Integer downloadCount;

    /**
     * 分享密码
     * <p>
     * 仅在"需要包含敏感信息"的场景下返回真实密码值（如管理员查看、创建者查看自己的分享），
     * 对外部访问者始终返回 null，确保密码不泄露。
     * </p>
     */
    @Schema(title = "分享密码")
    private String password;

    /**
     * 分享创建者的用户 ID
     * <p>
     * 仅在管理员查询分享列表时返回，普通用户查询时为空。
     * </p>
     */
    @Schema(title = "分享创建者用户ID")
    private Integer userId;

    /**
     * 分享创建者的用户名（登录账号）
     * <p>
     * 仅在管理员查询分享列表时由服务端根据 userId 查询后填充。
     * </p>
     */
    @Schema(title = "分享创建者用户名")
    private String username;

    /**
     * 分享创建者的昵称（显示名称）
     * <p>
     * 仅在管理员查询分享列表时由服务端根据 userId 查询后填充。
     * </p>
     */
    @Schema(title = "分享创建者昵称")
    private String nickname;
}
