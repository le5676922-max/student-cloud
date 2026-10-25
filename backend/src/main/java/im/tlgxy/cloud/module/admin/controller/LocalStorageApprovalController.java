package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.tenant.service.LocalStorageApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class LocalStorageApprovalController {
    private final LocalStorageApprovalService service;
    public record Submit(String reason) { }
    public record Review(boolean approve, String note) { }
    @GetMapping({"/api/workspace-storage/local-requests", "/admin/storage-local-requests"})
    public AjaxJson<List<LocalStorageApprovalService.Request>> list() { return AjaxJson.getSuccessData(service.list()); }
    @PostMapping("/api/workspace-storage/local-requests") @DemoDisable
    public AjaxJson<String> submit(@RequestBody Submit request) { return AjaxJson.getSuccessData(service.submit(request.reason())); }
    @PostMapping("/admin/storage-local-requests/{id}/review") @DemoDisable
    public AjaxJson<Void> review(@PathVariable String id, @RequestBody Review request) {
        service.review(id, request.approve(), request.note()); return AjaxJson.getSuccess();
    }
}
