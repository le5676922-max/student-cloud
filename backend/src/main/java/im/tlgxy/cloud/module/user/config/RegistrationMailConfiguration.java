package im.tlgxy.cloud.module.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Configuration
public class RegistrationMailConfiguration {
    @Bean("registrationMailSender")
    public JavaMailSender registrationMailSender(RegistrationMailProperties settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setUsername(settings.getUsername());
        sender.setPassword(settings.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(settings.isAuth()));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(settings.isSsl()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(settings.isStarttls()));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(settings.isStarttls()));
        int timeout = Math.max(1000, Math.min(settings.getTimeoutMs(), 15000));
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(timeout));
        properties.setProperty("mail.smtp.timeout", Integer.toString(timeout));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(timeout));
        properties.setProperty("mail.debug", "false");
        return sender;
    }
}
