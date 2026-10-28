package io.studentcloud.content.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Only this service's schema can be connected; no schema-qualified identity access is permitted. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentDatabaseConfiguration.DatabaseSettings.class)
public class ContentDatabaseConfiguration {
    @ConfigurationProperties("student-cloud.database")
    public record DatabaseSettings(String url, String username, String password, int maximumPoolSize) { }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(DatabaseSettings settings) {
        validateUrl(settings.url());
        if (settings.maximumPoolSize() < 1 || settings.maximumPoolSize() > 8)
            throw new IllegalArgumentException("Content database pool must have 1 to 8 connections");
        if (settings.url().startsWith("jdbc:mysql:") && (settings.password() == null || settings.password().isBlank()))
            throw new IllegalArgumentException("Configure the content database account password");
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("student-cloud-content");
        pool.setJdbcUrl(settings.url());
        pool.setUsername(Objects.requireNonNull(settings.username()));
        pool.setPassword(Objects.requireNonNullElse(settings.password(), ""));
        pool.setMaximumPoolSize(settings.maximumPoolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3000);
        pool.setValidationTimeout(1000);
        pool.setInitializationFailTimeout(5000);
        return pool;
    }

    static void validateUrl(String url) {
        if (url != null && url.length() <= 2048) {
            if (url.matches("jdbc:h2:mem:student_cloud_content(?:_[A-Za-z0-9]+)?(?:;MODE=MySQL)?(?:;DB_CLOSE_DELAY=-1)?(?:;DB_CLOSE_ON_EXIT=FALSE)?")) return;
            if (url.startsWith("jdbc:mysql://")) {
                try {
                    URI target = URI.create(url.substring(5));
                    if (target.getHost() != null && target.getUserInfo() == null && target.getFragment() == null
                            && target.getPath().matches("/student_cloud_content(?:_[A-Za-z0-9]+)?")) return;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        throw new IllegalArgumentException("Content service requires a dedicated student_cloud_content database");
    }
}
