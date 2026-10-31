package io.studentcloud.account;

/** 提供会话令牌的身份验证端口。 Validates a bearer session against the identity service; transport values never grant authority. */
@FunctionalInterface
public interface SessionAuthenticator {
    Actor authenticate(String token);
}
