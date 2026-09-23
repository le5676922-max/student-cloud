package im.tlgxy.cloud.module.storage.model.request.operator;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量移动或复制文件/文件夹请求类
 * <p>
 * 用于请求批量将文件或文件夹移动或复制到目标路径。
 * 注意：不支持跨存储策略操作，也不支持批量操作中
 * 源路径不相同或目标路径不相同的场景。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "(移动/复制)(文件/文件夹)请求")
public class BatchMoveOrCopyFileRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 源路径，表示要移动/复制的文件夹所在的父目录路径
     */
    @Schema(title = "请求路径", example = "/", description ="表示要移动的文件夹所在的文件夹")
    @NotBlank
    private String path;

    /**
     * 要移动/复制的文件夹/文件名称列表，支持多个
     */
    @Schema(title = "文件夹名称", example = "movie", description ="表示要移动的文件夹名称，支持多个")
    @NotEmpty
    private List<String> nameList;

    /**
     * 目标路径，表示要移动/复制到的目标文件夹路径
     */
    @Schema(title = "目标路径", example = "/", description ="表示要移动到的文件夹")
    @NotBlank
    private String targetPath;

    /**
     * 目标文件夹/文件名称列表，表示移动/复制后的新名称，支持多个
     */
    @Schema(title = "目标文件夹名称", example = "电影", description ="表示要移动到的文件夹名称，支持多个")
    @NotEmpty
    private List<String> targetNameList;

    /**
     * 源文件夹密码，如果源文件夹需要密码才能访问，则需提供
     */
    @Schema(title = "源文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String srcPathPassword;

    /**
     * 目标文件夹密码，如果目标文件夹需要密码才能访问，则需提供
     */
    @Schema(title = "目标文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
    private String targetPathPassword;

}
