package io.studentcloud.tasks;

/** Internal snapshot. Never expose another actor's payload through an HTTP list. */
public record TaskRecord(
        String id, long actorId, long workspaceId, TaskType type, String payload,
        TaskState state, int attempts, int maxAttempts, long availableAt,
        String workerId, Long leaseUntil, long fence, String result, String failureCode,
        long createdAt, long updatedAt, Long finishedAt) { }
