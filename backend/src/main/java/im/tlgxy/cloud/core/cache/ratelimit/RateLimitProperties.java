package im.tlgxy.cloud.core.cache.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties("tongligong-student-cloud.rate-limit")
public class RateLimitProperties {
    private String type = "auto";
    /** closed preserves a shared security boundary; local is an explicit availability trade-off. */
    private String failureMode = "closed";
    private int localMaxKeys = 10_000;
    private List<String> trustedProxyCidrs = new ArrayList<>();
}
