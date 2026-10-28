package io.studentcloud.course;

public record InvitationPreview(long id, String name, String description, boolean creator, boolean joined, Long expiresAt) { }
