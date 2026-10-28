package io.studentcloud.tasks;

/** The HTTP adapter must supply actor and workspace from authenticated server-side state. */
public record CreateTask(long actorId, long workspaceId, TaskType type, String payload, String idempotencyKey) { }
