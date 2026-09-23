package im.tlgxy.cloud.module.storage.model.request.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复制存储源请求类
 * <p>
 * 用于管理员复制已有的存储源配置。
 * 复制操作会创建一个新的存储源，保留原存储源的参数配置，
 * 但使用新的名称和别名（key）。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "复制存储源请求请求类")
public class CopyStorageSourceRequest {

    /**
     * 源存储源 ID，即要复制的存储源的唯一标识
     */
    @Schema(title = "存储源 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "存储源 id 不能为空")
    private Integer fromId;

    /**
     * 复制后新存储源的名称
     */
    @Schema(title = "复制后存储源名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotBlank(message = "复制后存储源名称不能为空")
    private String toName;

    /**
     * 复制后新存储源的别名（key），用于 URL 中展示
     */
    @Schema(title = "复制后存储源别名", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotBlank(message = "复制后存储源别名不能为空")
    private String toKey;



}
