package im.tlgxy.cloud.module.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Registration stays unavailable until a real sender has been configured. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tongligong-student-cloud.registration.mail")
public class RegistrationMailProperties {
    private boolean enabled;
    private String host = "smtp.qq.com";
    private int port = 465;
    private String username = "";
    private String password = "";
    private String from = "";
    private boolean auth = true;
    private boolean ssl = true;
    private boolean starttls;
    private int timeoutMs = 5000;
    /** Bound local SMTP workers and fleet-wide delivery attempts independently of account lookup. */
    private int maxConcurrentSends = 8;
    private int maxSendsPerMinute = 120;
    // A shared secret is recommended for multiple instances. Never expose it to the browser.
    private String codeSecret = "";
}
