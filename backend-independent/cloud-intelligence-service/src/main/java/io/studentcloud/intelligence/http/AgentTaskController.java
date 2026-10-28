package io.studentcloud.intelligence.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.intelligence.agent.AgentContentClient;
import io.studentcloud.intelligence.agent.AgentPlanService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/tasks")
public final class AgentTaskController {
    private final AgentPlanService plans;
    public AgentTaskController(AgentPlanService plans) { this.plans=plans; }
    public record PreviewInput(String type, String storageKey, String path, List<AgentContentClient.Step> steps,Long courseId,String tags) { }
    public record ConfirmInput(boolean confirmed) { }

    @PostMapping public ApiResponse<AgentPlanService.View> preview(@RequestBody PreviewInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        return ApiResponse.success(input.steps() == null
                ? plans.previewBulk(actor, input.storageKey(), input.path(), input.type(),input.courseId(),input.tags())
                : plans.preview(actor, input.storageKey(), input.steps()));
    }
    @GetMapping public ApiResponse<List<AgentPlanService.View>> list(HttpServletRequest request) {
        return ApiResponse.success(plans.list(AuthenticationFilter.actor(request)));
    }
    @GetMapping("/duplicates") public ApiResponse<AgentContentClient.DuplicateReport> duplicates(@RequestParam String storageKey,@RequestParam String path,HttpServletRequest request){
        return ApiResponse.success(plans.duplicates(AuthenticationFilter.actor(request),storageKey,path));
    }
    @GetMapping("/{id}") public ApiResponse<AgentPlanService.View> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(plans.get(AuthenticationFilter.actor(request), id));
    }
    @PostMapping("/{id}/confirm") public ApiResponse<AgentPlanService.View> confirm(@PathVariable String id,
            @RequestBody ConfirmInput input, HttpServletRequest request) {
        return ApiResponse.success(plans.confirm(AuthenticationFilter.actor(request), id, input.confirmed()));
    }
    @PostMapping("/{id}/cancel") public ApiResponse<AgentPlanService.View> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(plans.cancel(AuthenticationFilter.actor(request), id));
    }
    @PostMapping("/{id}/retry") public ApiResponse<AgentPlanService.View> retry(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(plans.retry(AuthenticationFilter.actor(request), id));
    }
}
