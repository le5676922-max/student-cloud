package im.tlgxy.cloud.module.storage.model.result;

import im.tlgxy.cloud.module.readme.model.enums.ReadmeDisplayModeEnum;
import im.tlgxy.cloud.module.storage.model.bo.StorageSourceMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 存储源配置信息响应类
 * <p>
 * 封装前端访问存储源时需要的配置信息。
 * 包括 readme 文档内容和显示模式、图片模式开关、
 * 用户权限映射、存储源元数据和用户根路径等。
 * 用于前端根据配置渲染不同的界面和功能。
 * </p>
 *
 * @author zhaojun
 */
@Schema(title="存储源设置响应类")
@Data
public class StorageSourceConfigResult {

    /**
     * readme 文本内容，支持 Markdown 语法
     */
	@Schema(title="readme 文本内容, 支持 md 语法.")
	private String readmeText;

    /**
     * readme 显示模式：top（顶部显示）、bottom（底部显示）、dialog（弹窗显示）
     */
	@Schema(title = "显示模式", requiredMode = Schema.RequiredMode.REQUIRED, example = "readme 显示模式，支持顶部显示: top, 底部显示:bottom, 弹窗显示: dialog")
	private ReadmeDisplayModeEnum readmeDisplayMode;

    /**
     * 是否默认开启图片模式（缩略图浏览模式）
     */
	@Schema(title = "是否默认开启图片模式", example = "true")
	private Boolean defaultSwitchToImgMode;

    /**
     * 当前用户对该存储源的操作权限映射
     * <p>
     * Key 为权限名称（如 upload、delete、rename 等），
     * Value 为布尔值表示是否拥有该权限。
     * </p>
     */
	private Map<String, Boolean> permission;

    /**
     * 存储源元数据，包含存储源的文件系统信息
     */
	@Schema(title = "存储源元数据")
	private StorageSourceMetadata metadata;

    /**
     * 当前用户在存储源中的根路径
     */
	private String rootPath;

}
