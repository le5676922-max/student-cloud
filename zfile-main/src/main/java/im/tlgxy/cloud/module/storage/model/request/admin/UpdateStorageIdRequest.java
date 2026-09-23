package im.tlgxy.cloud.module.storage.model.request.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新存储源 ID 请求类
 * <p>
 * 用于管理员修改存储源的唯一标识 ID。
 * 将存储源从原 ID 修改为新 ID，通常用于 ID 冲突或重新编排的场景。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "更新存储源 id 请求类")
public class UpdateStorageIdRequest {

    /**
     * 存储源原 ID，即当前要修改的存储源 ID
     */
    @Schema(title = "存储源原 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotBlank(message = "源 id 不能为空")
    private Integer updateId;


    /**
     * 存储源新 ID，修改后的目标 ID
     */
    @Schema(title = "存储源新 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotBlank(message = "修改后的 id 不能为空")
    private Integer newId;

}
