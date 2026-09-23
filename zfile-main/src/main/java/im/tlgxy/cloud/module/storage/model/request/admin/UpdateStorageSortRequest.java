package im.tlgxy.cloud.module.storage.model.request.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新存储源排序值请求类
 * <p>
 * 用于管理员调整存储源在列表中的显示顺序。
 * 排序值越小，存储源在列表中的位置越靠前。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "更新存储源排序值请求类")
public class UpdateStorageSortRequest {

    /**
     * 存储源 ID，用于标识要调整排序的存储源
     */
    @Schema(title = "存储源 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotBlank(message = "存储源 id 不能为空")
    private Integer id;


    /**
     * 排序值，值越小越靠前显示
     */
    @Schema(title = "排序值，值越小越靠前", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    @NotBlank(message = "排序值不能为空")
    private Integer orderNum;

}
