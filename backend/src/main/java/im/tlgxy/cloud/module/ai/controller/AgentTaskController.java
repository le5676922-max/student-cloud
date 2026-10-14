package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import im.tlgxy.cloud.module.ai.service.AgentTaskService;
import im.tlgxy.cloud.module.ai.service.AutomationFileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("/api/ai/tasks")
public class AgentTaskController {
    private final AgentTaskService tasks;
    @GetMapping public AjaxJson<List<Automation.View>> list() { return AjaxJson.getSuccessData(tasks.list()); }
    @GetMapping("/{id}") public AjaxJson<Automation.View> get(@PathVariable String id) { return AjaxJson.getSuccessData(tasks.get(id)); }
    @GetMapping("/duplicates") @ApiLimit(timeout = 60, maxCount = 10)
    public AjaxJson<AutomationFileService.DuplicateReport> duplicates(@RequestParam String storageKey, @RequestParam(defaultValue = "/") String path) {
        return AjaxJson.getSuccessData(tasks.duplicates(storageKey, path));
    }
    @PostMapping @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<Automation.View> preview(@Valid @RequestBody Automation.Request request) { return AjaxJson.getSuccessData(tasks.preview(request)); }
    @PostMapping("/{id}/confirm") @ApiLimit(timeout = 60, maxCount = 10) @DemoDisable
    public AjaxJson<Automation.View> confirm(@PathVariable String id, @Valid @RequestBody Automation.Confirmation request) {
        return AjaxJson.getSuccessData(tasks.execute(id, request.confirmed()));
    }
    @PostMapping("/{id}/cancel") public AjaxJson<Automation.View> cancel(@PathVariable String id) { return AjaxJson.getSuccessData(tasks.cancel(id)); }
    @PostMapping("/{id}/retry") @ApiLimit(timeout = 60, maxCount = 10)
    public AjaxJson<Automation.View> retry(@PathVariable String id) { return AjaxJson.getSuccessData(tasks.retryFailed(id)); }
}
