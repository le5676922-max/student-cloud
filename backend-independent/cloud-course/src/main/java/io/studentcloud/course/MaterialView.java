package io.studentcloud.course;

/**
 * 返回课程资料的展示名称、文件大小与发布状态。
 * Display metadata only; physical object names and uploader identities stay private.
 */
public record MaterialView(long id, String filename, long sizeBytes, String status, long createdAt) { }
