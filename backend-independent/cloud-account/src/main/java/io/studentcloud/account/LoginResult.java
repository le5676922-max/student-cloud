package io.studentcloud.account;

import java.time.Instant;

/** 承载登录成功后的账号、会话令牌和到期时间。 The opaque token is returned once. Only its SHA-256 digest is stored. */
public record LoginResult(AccountView account, String token, Instant expiresAt) { }
