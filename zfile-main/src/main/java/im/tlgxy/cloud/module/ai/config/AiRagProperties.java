package im.tlgxy.cloud.module.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 本地 RAG 检索配置。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "tongligong-student-cloud.ai.rag")
public class AiRagProperties {

    /** 是否启用项目知识库。 */
    private boolean enabled = true;

    /** 知识库 JSON 文件位置。 */
    private String adminLocation = "classpath:ai/knowledge/admin-knowledge.json";

    private String userLocation = "classpath:ai/knowledge/user-knowledge.json";

    /** 每次最多召回的知识条目数。 */
    @Min(1)
    @Max(8)
    private int topK = 4;

    /** Only return documents whose relevance score reaches this threshold. */
    @Min(0)
    private int minScore = 20;

    /** 注入模型提示词的最大知识库字符数。 */
    @Min(1000)
    @Max(12000)
    private int maxContextChars = 5000;
}
