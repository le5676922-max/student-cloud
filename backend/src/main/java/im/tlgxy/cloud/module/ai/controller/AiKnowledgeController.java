package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.ai.model.knowledge.KnowledgeAudience;
import im.tlgxy.cloud.module.ai.model.result.AiKnowledgeResult;
import im.tlgxy.cloud.module.ai.service.ProjectKnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AI Agent 项目知识库接口。 */
@Tag(name = "AI Agent 知识库")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/knowledge")
public class AiKnowledgeController {

    private final ProjectKnowledgeService projectKnowledgeService;

    /** 查询 RAG 实际召回的项目资料，方便调试知识库。 */
    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 30)
    @Operation(summary = "检索 AI Agent 项目知识库")
    @GetMapping("/search")
    public AjaxJson<List<AiKnowledgeResult>> search(
            @RequestParam
            @NotBlank(message = "检索问题不能为空")
            @Size(max = 500, message = "检索问题不能超过 500 个字符")
            String question) {
        KnowledgeAudience audience = projectKnowledgeService.resolveCurrentAudience();
        return AjaxJson.getSuccessData(projectKnowledgeService.search(question, audience));
    }
}
