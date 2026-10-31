package io.studentcloud.tasks;

/** 表示工作节点持有的有限任务租约及防止旧执行者写回的凭证。
 *
 * A fencing token must accompany every worker mutation; an expired token cannot be revived.
 */
public record TaskLease(TaskRecord task) {
    public String id() { return task.id(); }
    public String workerId() { return task.workerId(); }
    public long fence() { return task.fence(); }
    public long leaseUntil() { return task.leaseUntil(); }
}
