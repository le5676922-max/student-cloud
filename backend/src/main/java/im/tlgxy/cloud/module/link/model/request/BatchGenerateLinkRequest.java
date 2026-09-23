package im.tlgxy.cloud.module.link.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量生成直链/短链请求参数类
 * <p>
 * 封装批量生成链接所需的请求参数，包括存储源标识、文件路径列表和链接有效期。
 * 使用 Jakarta Validation 注解对请求参数进行校验，确保数据的合法性。
 * <p>
 * 该请求类同时用于直链批量生成和短链批量生成两个接口。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "批量生成直链请求类")
public class BatchGenerateLinkRequest {

    /**
     * 存储源 key，用于标识要生成链接的存储源，不能为空
     */
    @NotBlank(message = "存储源 key 不能为空")
    private String storageKey;

    /**
     * 要生成链接的文件路径列表，至少包含一个路径
     */
    @NotEmpty(message = "生成的文件路径不能为空")
    private List<String> paths;

    /**
     * 链接有效期（单位: 秒），不能为空。
     * 如果值为 -1 表示生成永久链接
     */
    @NotNull(message = "过期时间不能为空")
    private Long expireTime;
}