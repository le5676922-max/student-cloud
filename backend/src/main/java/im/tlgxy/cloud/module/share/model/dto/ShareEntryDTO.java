package im.tlgxy.cloud.module.share.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分享条目数据传输对象（DTO）
 * <p>
 * 用于在创建或修改分享链接时，描述分享中包含的单个文件或文件夹条目。
 * 一个分享链接可以包含多个 ShareEntryDTO，共同组成分享的内容列表。
 * 该 DTO 主要在 API 请求和响应中使用，配合 {@link ShareEntryTypeEnum} 区分条目类型。
 * </p>
 *
 * <p><b>使用场景：</b></p>
 * <ul>
 *   <li>创建分享链接时，前端传递要分享的文件/文件夹列表</li>
 *   <li>查看分享详情时，后端返回分享包含的条目信息</li>
 * </ul>
 *
 * <p><b>注解说明：</b></p>
 * <ul>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} — 反序列化时忽略 JSON 中未知的字段，提高接口兼容性</li>
 *   <li>{@code @NotBlank} / {@code @NotNull} — 配合 Jakarta Validation 进行参数校验</li>
 * </ul>
 *
 * @author zhaojun
 * @see ShareEntryTypeEnum
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "分享条目")
public class ShareEntryDTO {

    /**
     * 条目名称
     * <p>
     * 文件或文件夹的名称，例如 "document.pdf" 或 "photos"。
     * 不能为空字符串或仅包含空白字符。
     * </p>
     */
    @Schema(title = "条目名称", example = "document.pdf")
    @NotBlank(message = "分享条目名称不能为空")
    private String name;

    /**
     * 条目类型
     * <p>
     * 标识该条目是文件还是文件夹，取值为 {@link ShareEntryTypeEnum#FILE} 或 {@link ShareEntryTypeEnum#FOLDER}。
     * 不能为 null。
     * </p>
     */
    @Schema(title = "条目类型", description = "FILE/FOLDER")
    @NotNull(message = "分享条目类型不能为空")
    private ShareEntryTypeEnum type;

}
