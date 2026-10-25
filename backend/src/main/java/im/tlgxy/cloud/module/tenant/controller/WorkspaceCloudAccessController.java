package im.tlgxy.cloud.module.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.tenant.service.WorkspaceCloudAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @SaCheckLogin @RequiredArgsConstructor
@RequestMapping("/api/workspace-cloud")
public class WorkspaceCloudAccessController {
    private final WorkspaceCloudAccessService service;
    public record Apply(String reason) { }
    @GetMapping public AjaxJson<List<WorkspaceCloudAccessService.Cloud>> available() { return AjaxJson.getSuccessData(service.available()); }
    @PostMapping("/{id}/apply") @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<String> apply(@PathVariable int id, @RequestBody(required=false) Apply request) {
        return AjaxJson.getSuccessData(service.apply(id, request == null ? null : request.reason()));
    }
    @GetMapping("/requests") public AjaxJson<List<WorkspaceCloudAccessService.Application>> requests() { return AjaxJson.getSuccessData(service.pending()); }
    @PostMapping("/requests/{id}/review") @DemoDisable @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<Void> review(@PathVariable String id, @RequestBody WorkspaceCloudAccessService.Review request) {
        service.review(id, request); return AjaxJson.getSuccess();
    }
}
