package im.tlgxy.cloud.module.storage.model.request.operator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重命名文件请求类
 * <p>
 * 用于在指定存储源的指定路径下重命名一个文件。
 * 需要提供原文件名和新文件名，支持对密码保护的文件夹中的文件进行操作。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "重命名文件请求类")
public class RenameFileRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 文件所在目录路径，默认为根路径 "/"
     */
    @Schema(title = "请求路径", example = "/", description ="表示在哪个文件夹下重命名文件")
    private String path = "/";

    /**
     * 原文件名称，不能为空
     */
    @Schema(title = "重命名的原文件名称", example = "test.txt")
    @NotBlank(message = "原文件名不能为空")
    private String name;

    /**
     * 重命名后的新文件名称，不能为空
     */
    @Schema(title = "重命名后的文件名称", example = "text-1.txt")
    @NotBlank(message = "新文件名不能为空")
    private String newName;

    /**
     * 文件夹密码，如果文件夹需要密码才能访问，则需提供
     */
    @Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String password;

}
