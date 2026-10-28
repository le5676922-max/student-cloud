package io.studentcloud.platform.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("student-cloud.mail")
public record MailSettings(boolean enabled,String host,int port,String username,String password,boolean auth,boolean ssl,boolean starttls) { }
