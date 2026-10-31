package io.studentcloud.account;

/** 承载对外返回的账号基本资料。 Public account information; password encoding and session versions are not exposed. */
public record AccountView(long id, String accountId, String username, String email, Role role,
                          Long workspaceId, boolean enabled) { }
