package io.studentcloud.tasks;

/** 定义任务排队、运行、成功、失败和取消状态。 */
public enum TaskState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
