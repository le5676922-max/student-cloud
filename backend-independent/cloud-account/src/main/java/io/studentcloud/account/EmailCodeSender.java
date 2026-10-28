package io.studentcloud.account;

/** Delivery port. Implementations must not log the code, credentials or the supplier's sensitive response. */
public interface EmailCodeSender {
    boolean available();
    void send(String email, EmailPurpose purpose, String code);
}
