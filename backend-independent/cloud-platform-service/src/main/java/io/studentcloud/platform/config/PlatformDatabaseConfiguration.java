package io.studentcloud.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(PlatformDatabaseConfiguration.DatabaseSettings.class)
public class PlatformDatabaseConfiguration {
    @ConfigurationProperties("student-cloud.database")
    public record DatabaseSettings(String url,String username,String password,int maximumPoolSize) { }
    @Bean(destroyMethod="close") HikariDataSource dataSource(DatabaseSettings settings) {
        String url=settings.url();
        boolean accepted=url!=null && url.matches("jdbc:h2:mem:student_cloud_platform(?:_[A-Za-z0-9]+)?(?:;MODE=MySQL)?(?:;DB_CLOSE_DELAY=-1)?(?:;DB_CLOSE_ON_EXIT=FALSE)?");
        if(url!=null && url.startsWith("jdbc:mysql://")) {
            try {
                URI target=URI.create(url.substring(5));
                accepted=target.getHost()!=null && target.getUserInfo()==null && target.getFragment()==null
                        && target.getPath().matches("/student_cloud_platform(?:_[A-Za-z0-9]+)?");
            } catch(IllegalArgumentException invalid) { accepted=false; }
            if(settings.password()==null || settings.password().isBlank()) throw new IllegalArgumentException("Set the dedicated platform database password");
        }
        if(!accepted) throw new IllegalArgumentException("Platform service requires its own student_cloud_platform database");
        if(settings.maximumPoolSize()<1 || settings.maximumPoolSize()>8) throw new IllegalArgumentException("Platform connection pool must be between 1 and 8");
        var pool=new HikariDataSource();
        pool.setPoolName("student-cloud-platform"); pool.setJdbcUrl(url);pool.setUsername(settings.username());pool.setPassword(settings.password());
        pool.setMaximumPoolSize(settings.maximumPoolSize());pool.setMinimumIdle(0);pool.setConnectionTimeout(2500);pool.setValidationTimeout(1000);
        pool.setInitializationFailTimeout(5000);return pool;
    }
}
