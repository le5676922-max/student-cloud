package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.ai.model.automation.AgentSchedule;
import im.tlgxy.cloud.module.ai.model.automation.AgentScheduleRequest;
import im.tlgxy.cloud.module.ai.service.AgentScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("/api/ai/schedules")
public class AgentScheduleController {
    private final AgentScheduleService schedules;

    @GetMapping public AjaxJson<List<AgentSchedule>> list() { return AjaxJson.getSuccessData(schedules.list()); }

    @PostMapping @ApiLimit(timeout = 60, maxCount = 10)
    public AjaxJson<AgentSchedule> create(@Valid @RequestBody AgentScheduleRequest request) {
        return AjaxJson.getSuccessData(schedules.create(request));
    }

    @PostMapping("/{id}/enabled")
    public AjaxJson<AgentSchedule> setEnabled(@PathVariable String id, @RequestParam boolean value) {
        return AjaxJson.getSuccessData(schedules.setEnabled(id, value));
    }

    @PostMapping("/{id}/preview") @ApiLimit(timeout = 60, maxCount = 5)
    public AjaxJson<AgentScheduleService.PreviewResult> previewNow(@PathVariable String id) {
        return AjaxJson.getSuccessData(schedules.previewNow(id));
    }
}
