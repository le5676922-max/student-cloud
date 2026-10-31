package io.studentcloud.assistant;

/** 返回个人长期记忆的启用状态与数量。 */
public record MemorySettings(boolean enabled, long count) { }
