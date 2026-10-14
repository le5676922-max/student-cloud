package im.tlgxy.cloud.module.storage.model.request.base;

import cn.hutool.core.util.StrUtil;
import im.tlgxy.cloud.core.validation.StringListValue;
import im.tlgxy.cloud.core.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

/**
 * 获取文件夹下文件列表请求类
 * <p>
 * 用于请求获取指定存储源中某个文件夹下的文件列表。
 * 支持按文件名、大小、时间排序，支持升序和降序排列，
 * 同时支持对有密码保护的文件夹进行访问。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "获取文件夹下文件列表请求类")
public class FileListRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 请求的文件夹路径
     */
    @Schema(title = "请求路径", example = "/")
    private String path;

    /**
     * 文件夹密码，如果文件夹需要密码才能访问，则需提供正确的密码
     */
	@Schema(title = "文件夹密码, 如果文件夹需要密码才能访问，则支持请求密码", example = "123456")
	private String password;

    /**
     * 排序字段，可选值：name（按名称）、size（按大小）、time（按时间）
     */
	@StringListValue(message = "排序字段参数异常，只能是 name、size、time", vals = {"name", "size", "time"})
	private String orderBy;

    /**
     * 排序方向，可选值：asc（升序）、desc（降序）
     */
	@StringListValue(message = "排序顺序参数异常，只能是 asc 或 desc", vals = {"asc", "desc"})
	private String orderDirection;

    /** Optional for compatibility with folder pickers and other existing callers. */
    @Min(1) @Max(500)
    private Integer pageSize;

    @Size(max = 100)
    private String pageCursor;

    @Size(max = 200)
    private String nameQuery;

    @Schema(title = "强制刷新目录元数据，翻页可复用短时快照")
    private boolean refreshMetadata;
    @Schema(title = "快速浏览：仅存储源顺序，不提供全局排序/搜索或总数")
    private boolean nativePagination;

    /**
     * 处理默认值
     * <p>
     * 如果路径为空，则默认为根路径 "/"；
     * 如果排序字段为空，则默认为按名称（name）排序；
     * 如果排序方向为空，则默认为升序（asc）；
     * 同时自动补全路径格式。
     * </p>
     */
    public void handleDefaultValue() {
        // 路径为空时默认为根路径
        if (StringUtils.isEmpty(path)) {
            path = "/";
        }
        // 排序字段为空时默认按名称排序
        if (!nativePagination && StringUtils.isEmpty(orderBy)) {
            orderBy = "name";
        }
        // 排序方向为空时默认升序
        if (!nativePagination && StringUtils.isEmpty(orderDirection)) {
            orderDirection = "asc";
        }

        // 自动补全路径, 如 a 补全为 /a/
        path = StringUtils.concat(path);
    }

}
