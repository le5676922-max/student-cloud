package im.tlgxy.cloud.module.storage.model.request.base;

import im.tlgxy.cloud.core.util.PatternMatcherUtils;
import im.tlgxy.cloud.module.storage.model.enums.SearchFolderModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 搜索存储源中文件请求类
 * <p>
 * 用于在指定存储源中搜索文件。支持按关键字搜索，
 * 可指定搜索模式（如全局搜索或当前目录搜索），
 * 并支持密码缓存，用于在搜索结果中访问受密码保护的文件夹。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "搜索存储源中文件请求类")
public class SearchStorageRequest {

    /**
     * 存储源别名（key），用于在 URL 中唯一标识一个存储源，不能为空
     */
    @Schema(title = "存储源 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "local")
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 搜索关键字，用于匹配文件名
     */
    @Schema(title = "搜索关键字", requiredMode = Schema.RequiredMode.REQUIRED, example = "png")
    private String searchKeyword;

    /**
     * 搜索模式，如 search_all（全局搜索）或 search_current（当前目录搜索）
     */
    @Schema(title = "搜索模式", requiredMode = Schema.RequiredMode.REQUIRED, example = "search_all")
    private SearchFolderModeEnum searchMode;

    /**
     * 搜索的起始路径
     */
    @Schema(title = "搜索路径", example = "/")
    private String path;

    /**
     * 密码缓存，Key 为路径表达式，Value 为对应的密码
     * <p>
     * 用于在搜索过程中自动匹配受密码保护的文件夹路径，
     * 避免反复要求用户输入密码。
     * </p>
     */
    @Schema(title = "密码缓存")
    private Map<String, String> passwordCache;


    /**
     * 根据文件夹路径获取缓存的密码
     * <p>
     * 遍历密码缓存中的所有条目，使用 glob 模式匹配判断
     * 当前请求路径是否与缓存的路径表达式匹配。
     * </p>
     *
     * @param   path
     *          文件夹路径
     *
     * @return  匹配到的密码，如果没找到则返回 null
     */
    public String getPathPasswordCache(String path) {
        // 密码缓存为空时直接返回 null
        if (passwordCache == null) {
            return null;
        }
        // 遍历密码缓存，匹配路径表达式
        for (Map.Entry<String, String> entry : passwordCache.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // 判断当前请求路径是否和规则路径表达式匹配
            boolean match = PatternMatcherUtils.testCompatibilityGlobPattern(key, path);
            if (match) {
                return value;
            }
        }

        return null;
    }


}
