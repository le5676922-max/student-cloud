package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceStorageProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/overview/workspaces")
public class WorkspaceStorageProvisioningController {
    private final TenantSecurityService security;
    private final WorkspaceStorageProvisioningService storage;

    @DemoDisable
    @PostMapping("/{tenantId}/storage")
    public AjaxJson<WorkspaceStorageProvisioningService.ProvisionedStorage> provision(@PathVariable int tenantId) {
        security.requireSystemAdmin();
        return AjaxJson.getSuccessData(storage.provisionExistingWorkspace(tenantId));
    }
}
