package im.tlgxy.cloud.module.link.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量生成链接响应结果类
 * <p>
 * 封装批量生成直链或短链后的单个结果信息。
 * 每条结果包含一个生成的链接地址（可能是直链地址或短链地址，取决于调用的接口）。
 * <p>
 * 使用 Lombok 注解自动生成：
 * <ul>
 *   <li>{@link Data}：getter/setter/toString/equals/hashCode 方法</li>
 *   <li>{@link AllArgsConstructor}：全参构造函数</li>
 * </ul>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "批量生成直链结果类")
@AllArgsConstructor
public class BatchGenerateLinkResponse {

    /**
     * 生成的链接地址，可能是直链地址或短链地址
     */
    private String address;

}