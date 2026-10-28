package io.studentcloud.assistant;

public record MemoryView(long id, String content, long version, long createdAt, long updatedAt) { }
