package io.studentcloud.tasks;

/** A fencing token must accompany every worker mutation; an expired token cannot be revived. */
public record TaskLease(TaskRecord task) {
    public String id() { return task.id(); }
    public String workerId() { return task.workerId(); }
    public long fence() { return task.fence(); }
    public long leaseUntil() { return task.leaseUntil(); }
}
