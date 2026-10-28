package io.studentcloud.intelligence.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates only the intelligence datastore; no account/course datasource or credentials are accepted. */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(IntelligenceDatabaseConfiguration.Settings.class)
public class IntelligenceDatabaseConfiguration {
    @ConfigurationProperties("student-cloud.database")
    public record Settings(String url, String username, String password, int maximumPoolSize) { }

    @Bean(destroyMethod="close")
    HikariDataSource dataSource(Settings settings) {
        validateUrl(settings.url());
        if (settings.maximumPoolSize() < 1 || settings.maximumPoolSize() > 8)
            throw new IllegalArgumentException("Intelligence pool size must be between 1 and 8");
        if (settings.url().startsWith("jdbc:mysql:") && (settings.password() == null || settings.password().isBlank()))
            throw new IllegalArgumentException("Set the intelligence MySQL password before starting");
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("student-cloud-intelligence");
        pool.setJdbcUrl(settings.url());
        pool.setUsername(Objects.requireNonNull(settings.username(), "Database username is required"));
        pool.setPassword(Objects.requireNonNullElse(settings.password(), ""));
        pool.setMaximumPoolSize(settings.maximumPoolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3000);
        pool.setValidationTimeout(1000);
        pool.setInitializationFailTimeout(5000);
        return pool;
    }

    static void validateUrl(String url) {
        if (url != null && url.matches("jdbc:h2:mem:student_cloud_intelligence(?:_[A-Za-z0-9]+)?(?:;MODE=MySQL)?(?:;DB_CLOSE_DELAY=-1)?(?:;DB_CLOSE_ON_EXIT=FALSE)?")) return;
        if (url != null && url.length() <= 2048 && url.startsWith("jdbc:mysql://")) {
            try {
                URI target = URI.create(url.substring(5));
                if (target.getHost() != null && target.getUserInfo() == null && target.getFragment() == null
                        && target.getPath().matches("/student_cloud_intelligence(?:_[A-Za-z0-9]+)?")) return;
            } catch (IllegalArgumentException ignored) { /* Never include credential-bearing configuration in failures. */ }
        }
        throw new IllegalArgumentException("Use a dedicated student_cloud_intelligence database");
    }
}
