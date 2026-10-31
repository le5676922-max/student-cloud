package io.studentcloud.account;

/** 定义验证码邮件的可用性检查与发送端口。 Delivery port. Implementations must not log the code, credentials or the supplier's sensitive response. */
public interface EmailCodeSender {
    boolean available();
    /** 向指定邮箱发送对应用途的验证码。 */
    void send(String email, EmailPurpose purpose, String code);
}
