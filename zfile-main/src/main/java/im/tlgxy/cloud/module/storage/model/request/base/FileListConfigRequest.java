package im.tlgxy.cloud.module.storage.model.request.base;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取文件夹配置信息请求类
 * <p>
 * 用于请求获取指定存储源中某个文件夹的配置信息，
 * 包括文件夹密码保护状态和 readme 文档内容等。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "获取文件夹参数请求类")
public class FileListConfigRequest {

	/**
	 * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
	 */
	@Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
	@NotBlank(message = "存储源 key 不能为空")
	private String storageKey;

	/**
	 * 请求的文件夹路径，默认为根路径 "/"
	 */
	@Schema(title = "请求路径", example = "/")
	private String path = "/";

	/**
	 * 文件夹密码，用于访问密码保护的文件夹
	 */
	@Schema(title = "文件夹密码", example = "123456")
	private String password;

}
