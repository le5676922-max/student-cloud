package im.tlgxy.cloud.module.storage.model.request.operator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 上传文件请求类
 * <p>
 * 用于请求向指定存储源的指定路径上传文件。
 * 需要提供目标路径、文件名和文件大小，
 * 支持向密码保护的文件夹中上传文件。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "上传文件请求类")
public class UploadFileRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 上传目标路径，表示文件上传到哪个文件夹下，默认为根路径 "/"
     */
    @Schema(title = "上传路径", example = "/movie", description ="表示上传文件到哪个路径")
    private String path = "/";

    /**
     * 上传的文件名，不能为空
     */
    @Schema(title = "上传的文件名", example = "test.mp4")
    @NotBlank(message = "上传的文件名不能为空")
    private String name;

    /**
     * 文件大小（字节），用于预检和分片上传
     */
    @Schema(title = "文件大小", example = "129102")
    private Long size;

    /**
     * 目标文件夹密码，如果目标文件夹需要密码才能访问，则需提供
     */
    @Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String password;

}
