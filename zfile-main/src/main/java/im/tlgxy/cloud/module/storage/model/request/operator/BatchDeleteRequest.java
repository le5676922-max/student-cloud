package im.tlgxy.cloud.module.storage.model.request.operator;

import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量删除文件/文件夹请求类
 * <p>
 * 用于请求批量删除存储源中的多个文件或文件夹。
 * 每个待删除项需指定路径、名称和类型（文件或文件夹），
 * 支持对密码保护的文件夹中的内容进行删除。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "删除文件夹请求类")
public class BatchDeleteRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 待删除的文件/文件夹列表，不能为空
     */
    @Schema(title = "删除的文件详情")
    @NotEmpty(message = "要删除的文件/文件夹不能为空")
    private List<DeleteItem> deleteItems;

    /**
     * 删除项内部类
     * <p>
     * 描述单个待删除的文件或文件夹的详细信息。
     * </p>
     */
    @Data
    public static class DeleteItem {

        /**
         * 文件/文件夹所在路径
         */
        private String path;

        /**
         * 文件/文件夹名称
         */
        private String name;

        /**
         * 类型：文件（FILE）或文件夹（FOLDER）
         */
        private FileTypeEnum type;

        /**
         * 文件夹密码，用于访问受密码保护的文件夹
         */
        private String password;

    }

}
