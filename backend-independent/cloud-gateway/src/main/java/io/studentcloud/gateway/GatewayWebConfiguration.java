package io.studentcloud.gateway;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class GatewayWebConfiguration {
    @Bean CorsWebFilter browserCors(@Value("${student-cloud.gateway.allowed-origins}") String configured) {
        var origins = Arrays.stream(configured.split(",")).map(String::trim).distinct().toList();
        if (origins.isEmpty() || origins.size() > 10) throw new IllegalArgumentException("Configure exact browser origins");
        for (String value : origins) {
            URI origin = URI.create(value);
            if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme())) || origin.getHost() == null
                    || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                    || !(origin.getPath() == null || origin.getPath().isEmpty())) {
                throw new IllegalArgumentException("Browser origins must be exact HTTP(S) origins");
            }
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(java.util.List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Content-Type", "Authorization", "tongligong-student-cloud-token",
                "axios-request", "axios-from", "Idempotency-Key", "X-Upload-Directory-Password"));
        cors.setExposedHeaders(java.util.List.of("Content-Disposition", "X-Request-Id"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
