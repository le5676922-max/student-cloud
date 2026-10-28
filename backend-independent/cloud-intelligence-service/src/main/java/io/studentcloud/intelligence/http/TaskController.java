package io.studentcloud.intelligence.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.tasks.TaskRecord;
import io.studentcloud.tasks.TaskService;
import io.studentcloud.tasks.TaskState;
import io.studentcloud.tasks.TaskType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account-scoped task read/cancel only. Claim and tool execution have no public route. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;
    public TaskController(TaskService tasks) { this.tasks = tasks; }

    @GetMapping
    public ApiResponse<List<TaskView>> list(@RequestParam(defaultValue="0") int offset,
                                              @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        return ApiResponse.success(tasks.list(AuthenticationFilter.actor(request).id(), offset, limit).stream().map(TaskView::of).toList());
    }
    @GetMapping("/{id}")
    public ApiResponse<TaskView> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(TaskView.of(tasks.get(AuthenticationFilter.actor(request).id(), id)));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<TaskView> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(TaskView.of(tasks.cancel(AuthenticationFilter.actor(request).id(), id)));
    }

    /** Worker identity, fencing tokens and source/tool payloads are not browser response data. */
    public record TaskView(String id, TaskType type, TaskState state, int attempts, int maxAttempts,
                           long availableAt, String result, String failureCode, long createdAt, long updatedAt, Long finishedAt) {
        static TaskView of(TaskRecord task) {
            return new TaskView(task.id(), task.type(), task.state(), task.attempts(), task.maxAttempts(), task.availableAt(),
                    task.result(), task.failureCode(), task.createdAt(), task.updatedAt(), task.finishedAt());
        }
    }
}
