package io.studentcloud.intelligence.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.intelligence.schedule.AgentScheduleService;
import io.studentcloud.intelligence.schedule.AgentScheduleService.CreateInput;
import io.studentcloud.intelligence.schedule.AgentScheduleService.PreviewResponse;
import io.studentcloud.intelligence.schedule.AgentScheduleService.ScheduleView;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Weekly automation endpoints create reviewable plans only; they never enqueue execution tasks. */
@RestController
@RequestMapping("/api/ai/schedules")
public final class AgentScheduleController {
    private final AgentScheduleService schedules;
    public AgentScheduleController(AgentScheduleService schedules){this.schedules=schedules;}

    @GetMapping public ApiResponse<List<ScheduleView>> list(HttpServletRequest request){
        return ApiResponse.success(schedules.list(AuthenticationFilter.actor(request)));
    }
    @PostMapping public ApiResponse<ScheduleView> create(@RequestBody CreateInput input,HttpServletRequest request){
        return ApiResponse.success(schedules.create(AuthenticationFilter.actor(request),input));
    }
    @PostMapping("/{id}/enabled") public ApiResponse<ScheduleView> enabled(@PathVariable long id,@RequestParam boolean value,HttpServletRequest request){
        return ApiResponse.success(schedules.setEnabled(AuthenticationFilter.actor(request),id,value));
    }
    @PostMapping("/{id}/preview") public ApiResponse<PreviewResponse> preview(@PathVariable long id,HttpServletRequest request){
        return ApiResponse.success(schedules.previewNow(AuthenticationFilter.actor(request),id));
    }
}
