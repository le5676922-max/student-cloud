package io.studentcloud.account;

/** Public account information; password encoding and session versions are not exposed. */
public record AccountView(long id, String accountId, String username, String email, Role role,
                          Long workspaceId, boolean enabled) { }
