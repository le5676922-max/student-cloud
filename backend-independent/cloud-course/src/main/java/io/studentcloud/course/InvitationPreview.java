package io.studentcloud.course;

/** 返回邀请码对应的课程摘要与当前操作者的加入状态。 */
public record InvitationPreview(long id, String name, String description, boolean creator, boolean joined, Long expiresAt) { }
