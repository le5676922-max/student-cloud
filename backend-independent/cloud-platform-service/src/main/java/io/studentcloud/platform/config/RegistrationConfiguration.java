package io.studentcloud.platform.config;

import io.studentcloud.account.*;
import io.studentcloud.platform.mail.MailSettings;
import io.studentcloud.platform.mail.SmtpEmailCodeSender;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MailSettings.class)
public class RegistrationConfiguration {
    @Bean EmailCodeSender emailSender(MailSettings settings){return new SmtpEmailCodeSender(settings);}
    @Bean @DependsOn("platformSchema") EmailCodeService emailCodes(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,
            EmailCodeSender sender,@Value("${student-cloud.services.internal-key}") String key){return new EmailCodeService(jdbc,tx,clock,sender,key);}
    @Bean @DependsOn("platformSchema") WorkspaceEnrollmentService enrollment(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock){return new WorkspaceEnrollmentService(jdbc,tx,clock);}
    @Bean @DependsOn("platformSchema") PublicRegistrationService publicRegistration(JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock,
            EmailCodeService codes,WorkspaceEnrollmentService enrollment,@Value("${student-cloud.registration.enabled:true}") boolean enabled,
            @Value("${student-cloud.password-reset.enabled:true}") boolean reset){return new PublicRegistrationService(jdbc,tx,clock,codes,enrollment,enabled,reset);}
}
