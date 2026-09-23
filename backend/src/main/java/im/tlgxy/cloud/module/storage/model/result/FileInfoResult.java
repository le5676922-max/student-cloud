package im.tlgxy.cloud.module.storage.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 文件列表信息结果类
 * <p>
 * 封装了文件列表查询的响应结果。
 * 包含文件/文件夹列表和当前目录的密码路径表达式，
 * 用于前端判断是否需要用户输入密码。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(title="文件列表信息结果类")
@AllArgsConstructor
public class FileInfoResult {

	/**
	 * 文件/文件夹列表
	 */
	@Schema(title="文件列表")
	private List<FileItemResult> files;

	/**
	 * 当前目录的密码路径表达式，用于匹配需要密码访问的路径
	 */
	@Schema(title="当前目录密码路径表达式")
	private String passwordPattern;

}
