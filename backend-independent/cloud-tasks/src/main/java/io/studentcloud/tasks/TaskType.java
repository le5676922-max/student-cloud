package io.studentcloud.tasks;

/** 声明可持久化的任务类别，不授予任意工具执行权限。
 *
 * Accepted persistence categories, not permission to execute arbitrary tools.
 */
public enum TaskType {
    DOCUMENT_INDEX,
    FILE_ORGANIZE,
    MODEL_ANSWER
}
