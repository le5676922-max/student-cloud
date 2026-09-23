package im.tlgxy.cloud.module.share.model.request;

import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.enums.ShareTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Date;
import java.util.List;

/**
 * 创建分享链接请求对象
 * <p>
 * 用于接收前端创建分享链接时提交的数据。包含存储源信息、分享路径、
 * 分享条目列表、密码保护、过期时间、分享类型以及自定义分享标识等完整参数。
 * 所有必填字段均通过 Jakarta Validation 注解进行参数校验。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "创建分享链接请求")
public class CreateShareLinkRequest {

    /**
     * 存储源 key，用于标识文件所属的存储源（如本地存储、阿里云 OSS 等）
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 分享的根目录路径，表示从存储源的哪个目录开始分享
     */
    @Schema(title = "分享所在目录", requiredMode = Schema.RequiredMode.REQUIRED, example = "/documents")
    @NotBlank(message = "分享目录不能为空")
    private String sharePath;

    /**
     * 分享条目列表，包含要分享的文件或文件夹信息
     * 使用 ShareEntryDTO 封装每个条目的名称、路径等详细信息
     */
    @Schema(title = "分享条目列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "分享条目不能为空")
    private List<ShareEntryDTO> shareEntries;

    /**
     * 分享密码（可选），设置后访问者需要输入密码才能查看分享内容
     */
    @Schema(title = "分享密码（可选）", example = "123456")
    private String password;

    /**
     * 分享过期时间（可选），超过此时间后分享链接将自动失效
     */
    @Schema(title = "过期时间（可选）", example = "2024-12-31T23:59:59")
    private Date expireDate;

    /**
     * 分享类型，枚举值，用于区分是文件分享还是文件夹分享等不同类型
     */
    @Schema(title = "分享类型", example = "FOLDER")
    @NotNull(message = "分享类型不能为空")
    private ShareTypeEnum shareType;

    /**
     * 自定义分享标识 key（可选），用于生成个性化分享链接
     * 如果不提供此字段，系统将自动生成一个唯一的分享 key
     */
    @Schema(title = "自定义分享 key（可选）", description = "如果不提供则自动生成", example = "my-custom-key")
    private String shareKey;
}
