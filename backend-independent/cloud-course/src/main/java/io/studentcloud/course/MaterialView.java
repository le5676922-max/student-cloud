package io.studentcloud.course;

/** Display metadata only; physical object names and uploader identities stay private. */
public record MaterialView(long id, String filename, long sizeBytes, String status, long createdAt) { }
