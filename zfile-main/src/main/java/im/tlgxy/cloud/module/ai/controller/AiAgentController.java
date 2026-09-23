package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult;
import im.tlgxy.cloud.module.ai.service.AiAgentService;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 学生云盘 AI Agent 接口。 */
@Tag(name = "AI Agent")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiAgentController {

    private final AiAgentService aiAgentService;
    private final AiAttachmentService aiAttachmentService;

    /**
     * 与 Agent 对话。
     *
     * <p>需要登录，且同一 IP 每分钟最多调用 20 次。</p>
     */
    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 20)
    @Operation(summary = "与 AI Agent 对话")
    @PostMapping("/chat")
    public AjaxJson<AiChatResult> chat(@Valid @RequestBody AiChatRequest request) {
        return AjaxJson.getSuccessData(aiAgentService.chat(request));
    }

    /** 与 Agent 对话，并临时读取本轮上传的附件。 */
    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 20)
    @Operation(summary = "携带附件与 AI Agent 对话")
    @PostMapping(value = "/chat/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxJson<AiChatResult> chatWithFiles(
            @Valid @RequestPart("request") AiChatRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return AjaxJson.getSuccessData(aiAgentService.chat(request, aiAttachmentService.extract(files)));
    }
}
