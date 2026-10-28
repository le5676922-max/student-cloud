package io.studentcloud.platform.mail;

import io.studentcloud.account.EmailCodeSender;
import io.studentcloud.account.EmailCodeService;
import io.studentcloud.account.EmailPurpose;
import io.studentcloud.common.ApplicationFault;
import java.util.Properties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Explicit SMTP adapter. Disabled by default; it never reads prior project passwords or logs mail content. */
public final class SmtpEmailCodeSender implements EmailCodeSender {
    private final boolean enabled;
    private final JavaMailSenderImpl sender;
    private final String from;

    public SmtpEmailCodeSender(MailSettings settings) {
        this.enabled=settings.enabled();this.sender=new JavaMailSenderImpl();
        if(!enabled){this.from="";return;}
        if(settings.host()==null || !settings.host().matches("[A-Za-z0-9.:-]{1,253}") || settings.port()<1 || settings.port()>65535)
            throw new IllegalArgumentException("Configure an explicit SMTP endpoint");
        boolean loopback="127.0.0.1".equals(settings.host()) || "localhost".equals(settings.host()) || "::1".equals(settings.host());
        if(!loopback && (!settings.auth() || !settings.ssl() && !settings.starttls()))
            throw new IllegalArgumentException("Remote SMTP requires authentication and TLS");
        if(settings.auth() && (settings.password()==null || settings.password().isBlank())) throw new IllegalArgumentException("Set the SMTP authorization password");
        this.from=EmailCodeService.normalizeEmail(settings.username());
        sender.setHost(settings.host());sender.setPort(settings.port());sender.setDefaultEncoding("UTF-8");
        sender.setUsername(settings.username());sender.setPassword(settings.password());
        Properties properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth",Boolean.toString(settings.auth()));
        properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(settings.ssl()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(settings.starttls()));
        properties.setProperty("mail.smtp.starttls.required",Boolean.toString(settings.starttls()));
        properties.setProperty("mail.smtp.connectiontimeout","3000");
        properties.setProperty("mail.smtp.timeout","5000");properties.setProperty("mail.smtp.writetimeout","5000");
        properties.setProperty("mail.debug","false");
    }
    @Override public boolean available(){return enabled;}
    @Override public void send(String email,EmailPurpose purpose,String code) {
        if(!enabled) throw new ApplicationFault("MAIL_UNAVAILABLE",503,"邮件服务尚未启用");
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(EmailCodeService.normalizeEmail(email));
        String operation=purpose==EmailPurpose.REGISTER?"注册":"找回密码";
        message.setSubject("学生云盘 - "+operation+"验证码");
        message.setText("你正在进行学生云盘"+operation+"。\n\n验证码："+code+"\n\n验证码10分钟内有效，请勿转发给他人。如果不是你本人操作，请忽略此邮件。");
        try {sender.send(message);}catch(RuntimeException failure){throw new ApplicationFault("MAIL_DELIVERY_FAILED",503,"邮件发送失败，请60秒后重试");}
    }
}
