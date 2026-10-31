package io.studentcloud.account;

/** 承载服务端确认的当前账号及空间身份。 Server-resolved identity; never populated from a client-supplied role. */
public record Actor(long id, String accountId, String username, String email, Role role, Long workspaceId) { }
