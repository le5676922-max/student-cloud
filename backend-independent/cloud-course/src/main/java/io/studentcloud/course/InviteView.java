package io.studentcloud.course;

/** Neither the raw code nor its digest is returned when listing invitations. */
public record InviteView(long id, Long expiresAt, Integer maxMembers, long usedCount, Long revokedAt, long createdAt) { }
