package im.tlgxy.cloud.module.storage.model.request.base;

import im.tlgxy.cloud.core.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 获取指定文件信息的请求类
 * <p>
 * 用于请求获取存储源中指定文件的详细信息。
 * 通过存储源 key 和文件路径定位目标文件，
 * 支持对密码保护的文件夹进行访问。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "获取指定文件信息的请求类")
public class FileItemRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 请求的文件路径，默认为根路径 "/"
     */
    @Schema(title = "请求路径", example = "/")
    private String path;

    /**
     * 文件夹密码，如果目标文件夹设置了密码保护，需要提供正确的密码才能访问
     */
	@Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
	private String password;

    /**
     * 处理默认值
     * <p>
     * 如果路径为空，则默认为根路径 "/"；
     * 同时自动补全路径格式，如 "a" 会补全为 "/a/"。
     * </p>
     */
    public void handleDefaultValue() {
        // 路径为空时默认为根路径
        if (StringUtils.isEmpty(path)) {
            path = "/";
        }
        // 自动补全路径, 如 a 补全为 /a/
        path = StringUtils.concat(path);
    }

}
