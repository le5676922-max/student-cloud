package io.studentcloud.assistant;

/** 表示单条个人记忆及其编辑版本和时间。 */
public record MemoryView(long id, String content, long version, long createdAt, long updatedAt) { }
