package io.studentcloud.account;

/** Validates a bearer session against the identity service; transport values never grant authority. */
@FunctionalInterface
public interface SessionAuthenticator {
    Actor authenticate(String token);
}
