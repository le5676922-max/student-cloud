package io.studentcloud.runtime;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public WebConfiguration(@Value("${student-cloud.web.allowed-origins}") String configured) {
        origins = Arrays.stream(configured.split(",")).map(String::trim).distinct().toArray(String[]::new);
        if (origins.length == 0 || origins.length > 10) throw new IllegalArgumentException("Configure explicit browser origins");
        for (String value : origins) {
            URI origin = URI.create(value);
            if ((!"http".equals(origin.getScheme()) && !"https".equals(origin.getScheme()))
                    || origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || origin.getFragment() != null || (origin.getPath() != null && !origin.getPath().isEmpty())) {
                throw new IllegalArgumentException("Each browser origin must be an exact HTTP(S) origin");
            }
        }
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "tongligong-student-cloud-token", "axios-request", "axios-from", "Idempotency-Key", "X-Upload-Directory-Password")
                .exposedHeaders("X-Request-Id", "Content-Disposition").allowCredentials(false).maxAge(600);
    }
}
