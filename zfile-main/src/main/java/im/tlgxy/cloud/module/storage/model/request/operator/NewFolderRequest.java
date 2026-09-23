package im.tlgxy.cloud.module.storage.model.request.operator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建文件夹请求类
 * <p>
 * 用于在指定存储源的指定路径下创建一个或多个文件夹。
 * 文件夹名称支持多级路径，如 "/a/b/c" 表示在指定路径下
 * 依次创建 a、b、c 三级目录。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "新建文件夹请求类")
public class NewFolderRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 父目录路径，表示在哪个文件夹下创建新文件夹，默认为根路径 "/"
     */
    @Schema(title = "请求路径", example = "/", description ="表示在哪个文件夹下创建文件夹")
    private String path = "/";

    /**
     * 新建的文件夹名称，支持多级路径，如 "/a/b/c" 会创建 a、b、c 三级目录
     */
    @Schema(title = "新建的文件夹名称", example = "/a/b/c", description ="文件夹名称支持多级，如：/a/b/c")
    @NotBlank(message = "新建的文件夹名称不能为空")
    private String name;

    /**
     * 父目录的文件夹密码，如果父目录需要密码才能访问，则需提供
     */
    @Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String password;

}
