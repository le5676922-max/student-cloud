package io.studentcloud.course;

/**
 * 返回课程邀请的有效期、使用次数与撤销状态。
 * Neither the raw code nor its digest is returned when listing invitations.
 */
public record InviteView(long id, Long expiresAt, Integer maxMembers, long usedCount, Long revokedAt, long createdAt) { }
