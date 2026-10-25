package im.tlgxy.cloud.module.ai.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/ai-feedback")
@SaCheckLogin
@RequiredArgsConstructor
public class AiFeedbackAdminController {
    private final AiFeedbackService feedback;
    @GetMapping @ApiLimit(timeout=60, maxCount=120)
    public AjaxJson<AiFeedbackService.Page> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String status) {
        return AjaxJson.getSuccessData(feedback.list(actor(), page, pageSize, status));
    }
    @GetMapping("/export") @ApiLimit(timeout=60, maxCount=6)
    public AjaxJson<List<AiFeedbackService.ExportEntry>> export() { return AjaxJson.getSuccessData(feedback.exportResolved(actor())); }
    @GetMapping("/{id}") @ApiLimit(timeout=60, maxCount=120)
    public AjaxJson<AiFeedbackService.Detail> get(@PathVariable String id) { return AjaxJson.getSuccessData(feedback.get(actor(), id)); }
    @PutMapping("/{id}") @ApiLimit(timeout=60, maxCount=60) @DemoDisable
    public AjaxJson<AiFeedbackService.Detail> review(@PathVariable String id, @RequestBody AiFeedbackService.Correction request) {
        return AjaxJson.getSuccessData(feedback.review(actor(), id, request));
    }
    private int actor() { return TlgxyCloudAuthUtil.getCurrentUserId(); }
}
