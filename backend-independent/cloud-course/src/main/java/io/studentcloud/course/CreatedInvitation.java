package io.studentcloud.course;

/**
 * 返回新建课程邀请及仅在创建时展示的原始邀请码。
 * Raw code is available only from this creation response.
 */
public record CreatedInvitation(long id, String code, Long expiresAt, Integer maxMembers, long usedCount, long createdAt) { }
