package io.studentcloud.account;

/** Server-resolved identity; never populated from a client-supplied role. */
public record Actor(long id, String accountId, String username, String email, Role role, Long workspaceId) { }
