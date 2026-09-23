package im.tlgxy.cloud.module.share.model.request;

import im.tlgxy.cloud.core.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 获取分享文件列表请求对象
 * <p>
 * 用于接收访问者浏览分享内容时提交的请求参数。包含分享链接标识、
 * 请求路径、密码验证信息以及排序参数。
 * 通过 {@link #handleDefaultValue()} 方法对可选参数设置默认值，
 * 并对路径进行规范化处理。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "获取分享文件列表请求")
public class ShareFileListRequest {

    /**
     * 分享链接的唯一标识 key，用于定位具体的分享记录
     */
    @Schema(title = "分享链接 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "abc12345")
    @NotBlank(message = "分享链接 key 不能为空")
    private String shareKey;

    /**
     * 请求的文件路径，相对于分享根目录
     * 默认为 "/"，表示查看分享根目录下的内容
     */
    @Schema(title = "请求路径", example = "/")
    private String path;

    /**
     * 分享密码（可选），用于验证需要密码保护的分享链接
     */
    @Schema(title = "分享密码", example = "123456")
    private String password;

    /**
     * 目录密码（可选），用于验证设置了单独密码保护的子目录
     */
    @Schema(title = "目录密码", example = "123456")
    private String folderPassword;

    /**
     * 排序字段，指定按哪个属性对文件列表进行排序
     * 默认为 "name"，即按文件名排序
     */
    @Schema(title = "排序字段", example = "name")
    private String orderBy;

    /**
     * 排序方向，支持 "asc"（升序）和 "desc"（降序）
     * 默认为 "asc"
     */
    @Schema(title = "排序方向", example = "asc")
    private String orderDirection;

    /**
     * 处理请求参数的默认值
     * <p>
     * 对可选的请求参数进行默认值填充：
     * <ul>
     *   <li>path 为空时默认为 "/"（分享根目录）</li>
     *   <li>orderBy 为空时默认为 "name"（按文件名排序）</li>
     *   <li>orderDirection 为空时默认为 "asc"（升序排列）</li>
     * </ul>
     * 同时调用 {@link StringUtils#concat(String)} 对路径进行规范化处理，
     * 确保路径以 "/" 开头和结尾，如将 "a" 补全为 "/a/"。
     * </p>
     */
    public void handleDefaultValue() {
        // 设置默认路径为分享根目录
        if (StringUtils.isEmpty(path)) {
            path = "/";
        }
        // 设置默认排序字段为文件名
        if (StringUtils.isEmpty(orderBy)) {
            orderBy = "name";
        }
        // 设置默认排序方向为升序
        if (StringUtils.isEmpty(orderDirection)) {
            orderDirection = "asc";
        }

        // 自动补全路径, 如 a 补全为 /a/
        path = StringUtils.concat(path);
    }
}
