package im.tlgxy.cloud.core.cache.metadata;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("tongligong-student-cloud.cache")
public class MetadataCacheProperties {
    private String type = "memory";
    private String namespace = "student-cloud";
    private Duration metadataTtl = Duration.ofSeconds(120);
    /** Aggregate dashboard statistics can be up to this old; never used for authorization. */
    private Duration overviewTtl = Duration.ofSeconds(10);
    private Redis redis = new Redis();

    @Getter
    @Setter
    public static class Redis {
        private String host = "127.0.0.1";
        private int port = 6379;
        private int database = 1;
        private String username = "";
        private String password = "";
        private boolean ssl;
        private Duration timeout = Duration.ofMillis(800);
    }
}
