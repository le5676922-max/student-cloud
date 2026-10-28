package io.studentcloud.course;

/** Raw code is available only from this creation response. */
public record CreatedInvitation(long id, String code, Long expiresAt, Integer maxMembers, long usedCount, long createdAt) { }
