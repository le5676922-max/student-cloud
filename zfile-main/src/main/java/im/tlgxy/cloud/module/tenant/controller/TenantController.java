package im.tlgxy.cloud.module.tenant.controller;

import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.request.SaveTenantRequest;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/tenants")
    public AjaxJson<List<Tenant>> list() {
        return AjaxJson.getSuccessData(tenantService.listAll());
    }

    @PostMapping("/tenant")
    @DemoDisable
    public AjaxJson<Tenant> save(@RequestBody @Valid SaveTenantRequest request) {
        return AjaxJson.getSuccessData(tenantService.save(request));
    }
}

