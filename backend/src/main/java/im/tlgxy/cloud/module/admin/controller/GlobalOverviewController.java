package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/overview")
public class GlobalOverviewController {
    private final GlobalOverviewService service;
    private final TenantSecurityService security;

    @GetMapping
    public AjaxJson<GlobalOverviewService.Overview> overview() {
        security.requireSystemAdmin();
        return AjaxJson.getSuccessData(service.overview());
    }

    @GetMapping("/workspaces")
    public AjaxJson<GlobalOverviewService.WorkspacePage> workspaces(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer workspaceId) {
        security.requireSystemAdmin();
        return AjaxJson.getSuccessData(service.workspaces(keyword, enabled, page, pageSize, workspaceId));
    }

    @GetMapping("/workspaces/{tenantId}/users")
    public AjaxJson<GlobalOverviewService.WorkspaceMemberPage> workspaceMembers(
            @PathVariable int tenantId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        security.requireSystemAdmin();
        return AjaxJson.getSuccessData(service.workspaceMembers(tenantId, role, keyword, page, pageSize));
    }
}
