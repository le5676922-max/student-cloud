package io.studentcloud.tasks;

/** 表示任务事务外发通知，不包含私有任务正文。
 *
 * Notification contents contain task identity and category, never the private task payload.
 */
public record TaskOutboxRecord(String id, String taskId, String eventType, String payload, long createdAt) { }
