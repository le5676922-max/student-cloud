package im.tlgxy.cloud.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Allowed browser origins for cross-origin API requests. */
@Data
@Component
@ConfigurationProperties(prefix = "tongligong-student-cloud.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of();

    public boolean isAllowed(String origin) {
        return origin != null && allowedOrigins.stream()
                .map(String::trim)
                .anyMatch(origin::equals);
    }
}
