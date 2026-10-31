package io.studentcloud.course;

/**
 * 返回课程成员的昵称、上传授权状态与加入时间。
 * The course roster deliberately omits email addresses, login names and personal workspace data.
 */
public record MemberView(long userId, String nickname, String uploadStatus, long joinedAt) { }
