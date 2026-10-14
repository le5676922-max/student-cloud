package im.tlgxy.cloud.module.storage.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter
@Component
@ConfigurationProperties(prefix = "tongligong-student-cloud.uploads.resumable")
public class ResumableUploadProperties {
    private boolean enabled = true;
    private String stagingRoot = "./data/upload-staging";
    /** Reserve twice the declared length to include the final assembly; no unlimited spill to disk. */
    private long quotaBytes = 20L * 1024 * 1024 * 1024;
}
