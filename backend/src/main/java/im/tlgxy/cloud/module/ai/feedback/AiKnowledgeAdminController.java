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
@RequestMapping("/admin/ai-knowledge")
@SaCheckLogin
@RequiredArgsConstructor
public class AiKnowledgeAdminController {
    private final AiKnowledgePublicationService knowledge;
    @GetMapping("/feedback/{id}") @ApiLimit(timeout=60, maxCount=120)
    public AjaxJson<AiKnowledgePublicationService.State> state(@PathVariable String id) {
        return AjaxJson.getSuccessData(knowledge.state(actor(), id));
    }
    @GetMapping("/history/{id}") @ApiLimit(timeout=60, maxCount=60)
    public AjaxJson<List<AiKnowledgePublicationService.Revision>> history(@PathVariable String id) {
        return AjaxJson.getSuccessData(knowledge.history(actor(), id));
    }
    @PostMapping("/refine") @ApiLimit(timeout=60, maxCount=6) @DemoDisable
    public AjaxJson<AiKnowledgePublicationService.Preview> refine(@RequestBody AiKnowledgePublicationService.RefineRequest request) {
        return AjaxJson.getSuccessData(knowledge.refine(actor(), request));
    }
    @PostMapping("/publish") @ApiLimit(timeout=60, maxCount=12) @DemoDisable
    public AjaxJson<List<AiKnowledgePublicationService.State>> publish(@RequestBody AiKnowledgePublicationService.PublishRequest request) {
        return AjaxJson.getSuccessData(knowledge.publish(actor(), request));
    }
    @PostMapping("/withdraw") @ApiLimit(timeout=60, maxCount=30) @DemoDisable
    public AjaxJson<AiKnowledgePublicationService.State> withdraw(@RequestBody AiKnowledgePublicationService.Withdrawal request) {
        return AjaxJson.getSuccessData(knowledge.withdraw(actor(), request));
    }
    private int actor() { return TlgxyCloudAuthUtil.getCurrentUserId(); }
}
