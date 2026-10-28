package io.studentcloud.tasks;

/** Notification contents contain task identity and category, never the private task payload. */
public record TaskOutboxRecord(String id, String taskId, String eventType, String payload, long createdAt) { }
