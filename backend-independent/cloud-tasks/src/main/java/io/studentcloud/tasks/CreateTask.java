package io.studentcloud.tasks;

/** 承载创建任务所需的业务参数，身份与空间由服务端填充。
 *
 * The HTTP adapter must supply actor and workspace from authenticated server-side state.
 */
public record CreateTask(long actorId, long workspaceId, TaskType type, String payload, String idempotencyKey) { }
