package im.tlgxy.cloud.module.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Administrator-only local OCR settings; never taken from an upload or a model tool argument. */
@Data
@Validated
@ConfigurationProperties(prefix = "tongligong-student-cloud.learning.ocr")
public class LearningOcrProperties {
    private boolean enabled = false;
    private String executable = "/usr/bin/tesseract";
    private String language = "chi_sim+eng";
    @Min(1) @Max(20) private int maxPages = 10;
    @Min(72) @Max(160) private int dpi = 120;
    @Min(1000) @Max(120000) private int maxChars = 60000;
    @Min(1) @Max(30) private int pageTimeoutSeconds = 15;
    @Min(1) @Max(180) private int totalTimeoutSeconds = 90;
    @Min(1) @Max(2) private int maxConcurrent = 1;
}
