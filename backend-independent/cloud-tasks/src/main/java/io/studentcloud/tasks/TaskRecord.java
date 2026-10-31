package io.studentcloud.tasks;

/** 保存任务状态、执行结果和错误信息，读取时必须限定所属账号。
 *
 * Internal snapshot. Never expose another actor's payload through an HTTP list.
 */
public record TaskRecord(
        String id, long actorId, long workspaceId, TaskType type, String payload,
        TaskState state, int attempts, int maxAttempts, long availableAt,
        String workerId, Long leaseUntil, long fence, String result, String failureCode,
        long createdAt, long updatedAt, Long finishedAt) { }
