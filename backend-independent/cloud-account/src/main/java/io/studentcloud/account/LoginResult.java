package io.studentcloud.account;

import java.time.Instant;

/** The opaque token is returned once. Only its SHA-256 digest is stored. */
public record LoginResult(AccountView account, String token, Instant expiresAt) { }
