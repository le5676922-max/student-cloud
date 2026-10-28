package io.studentcloud.course;

public record CourseView(long id, String name, String description, long quotaBytes, long usedBytes,
                         long materialCount, long memberCount, boolean enabled, boolean creator,
                         String uploadStatus, long createdAt, long updatedAt) { }
