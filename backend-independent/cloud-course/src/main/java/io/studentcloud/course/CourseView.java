package io.studentcloud.course;

/** 返回课程信息、容量占用、成员数量及当前操作者的课程权限状态。 */
public record CourseView(long id, String name, String description, long quotaBytes, long usedBytes,
                         long materialCount, long memberCount, boolean enabled, boolean creator,
                         String uploadStatus, long createdAt, long updatedAt) { }
