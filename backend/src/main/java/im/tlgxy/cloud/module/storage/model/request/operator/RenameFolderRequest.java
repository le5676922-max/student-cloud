package im.tlgxy.cloud.module.storage.model.request.operator;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重命名文件夹请求类
 * <p>
 * 用于在指定存储源的指定路径下重命名一个文件夹。
 * 需要提供原文件夹名称和新文件夹名称，
 * 支持对密码保护的父目录进行操作。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "重命名文件夹请求类")
public class RenameFolderRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 父目录路径，即要重命名的文件夹所在的父目录，默认为根路径 "/"
     */
    @Schema(title = "请求路径", example = "/", description ="表示在哪个文件夹下重命名文件夹")
    private String path = "/";

    /**
     * 原文件夹名称，不能为空
     */
    @Schema(title = "重命名的原文件夹名称", example = "movie")
    @NotBlank(message = "原文件夹名称不能为空")
    private String name;

    /**
     * 重命名后的新文件夹名称，不能为空
     */
    @Schema(title = "重命名后的文件名称", example = "music")
    @NotBlank(message = "新文件夹名称不能为空")
    private String newName;

    /**
     * 父目录的文件夹密码，如果父目录需要密码才能访问，则需提供
     */
    @Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String password;

}
