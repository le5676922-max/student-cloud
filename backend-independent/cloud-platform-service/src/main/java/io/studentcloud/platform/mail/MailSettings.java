package io.studentcloud.platform.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定验证码邮件所需的 SMTP 地址、凭据与传输配置。 */
@ConfigurationProperties("student-cloud.mail")
public record MailSettings(boolean enabled,String host,int port,String username,String password,boolean auth,boolean ssl,boolean starttls) { }
