package io.studentcloud.course;

/** The course roster deliberately omits email addresses, login names and personal workspace data. */
public record MemberView(long userId, String nickname, String uploadStatus, long joinedAt) { }
