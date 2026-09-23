package im.tlgxy.cloud.module.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * AI Agent 配置。
 *
 * <p>密钥通过环境变量传入，避免将敏感信息提交到代码仓库。</p>
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "tongligong-student-cloud.ai")
public class AiAgentProperties {

    /** 是否启用 AI Agent。 */
    private boolean enabled = true;

    /** DeepSeek 或其他 OpenAI 兼容服务的 API 密钥。 */
    private String apiKey;

    /** OpenAI 兼容接口的基础地址，默认使用 DeepSeek 官方 API。 */
    private String baseUrl = "https://api.deepseek.com";

    /** 聊天模型名称，默认使用支持工具调用的 DeepSeek V4 Flash。 */
    private String model = "deepseek-v4-flash";

    /** 单次请求超时时间。 */
    @Min(5)
    @Max(120)
    private int timeoutSeconds = 45;

    /** 发送给模型的最大历史消息数。 */
    @Min(0)
    @Max(12)
    private int maxHistory = 8;

    /** 单轮对话允许的最大工具调用轮数。 */
    @Min(1)
    @Max(8)
    private int maxToolRounds = 4;
}
