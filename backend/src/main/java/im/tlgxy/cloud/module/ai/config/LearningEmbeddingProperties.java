package im.tlgxy.cloud.module.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Separate from chat credentials; only an administrator can configure the external embedding provider. */
@Getter @Setter @ToString(exclude = "apiKey") @Validated
@ConfigurationProperties(prefix = "tongligong-student-cloud.learning.embedding")
public class LearningEmbeddingProperties {
    private boolean enabled = false;
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    @Min(32) @Max(4096) private int dimensions = 1024;
    @Min(1) @Max(10) private int timeoutSeconds = 10;
    @Min(1) @Max(2) private int maxConcurrent = 2;
    @DecimalMin("-1.0") @DecimalMax("1.0") private double minSimilarity = 0.25;
}
