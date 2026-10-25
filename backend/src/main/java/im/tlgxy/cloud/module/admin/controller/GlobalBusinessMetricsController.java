package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.admin.service.GlobalBusinessMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/business-metrics")
public class GlobalBusinessMetricsController {
    private final GlobalBusinessMetricsService service;
    @GetMapping
    public AjaxJson<GlobalBusinessMetricsService.Metrics> get(@RequestParam(required=false) Integer tenantId) {
        return AjaxJson.getSuccessData(service.get(tenantId));
    }
}
