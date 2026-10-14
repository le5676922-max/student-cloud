package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.model.request.AiChatRequest;
import im.tlgxy.cloud.module.ai.model.result.AiChatResult;
import im.tlgxy.cloud.module.ai.service.AiAgentService;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** 学生云盘 AI Agent 接口。 */
@Tag(name = "AI Agent")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiAgentController {

    private final AiAgentService aiAgentService;
    private final AiAttachmentService aiAttachmentService;
    private final ObjectMapper objectMapper;

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
        return AjaxJson.getSuccessData(aiAgentService.chat(request, List.of(), TlgxyCloudAuthUtil.getCurrentUserId()));
    }

    /** 与 Agent 对话，并临时读取本轮上传的附件。 */
    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 20)
    @Operation(summary = "携带附件与 AI Agent 对话")
    @PostMapping(value = "/chat/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxJson<AiChatResult> chatWithFiles(
            @Valid @RequestPart("request") AiChatRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return AjaxJson.getSuccessData(aiAgentService.chat(request, aiAttachmentService.extract(files), TlgxyCloudAuthUtil.getCurrentUserId()));
    }

    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 20)
    @PostMapping(value = "/chat/stream/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable String id, @Valid @RequestBody AiChatRequest request, HttpServletResponse response) {
        writeStream(id, request, List.of(), response);
    }

    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 20)
    @PostMapping(value = "/chat/files/stream/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamWithFiles(@PathVariable String id, @Valid @RequestPart("request") AiChatRequest request,
                               @RequestPart(value = "files", required = false) List<MultipartFile> files,
                               HttpServletResponse response) {
        writeStream(id, request, aiAttachmentService.extract(files), response);
    }

    @SaCheckLogin
    @ApiLimit(timeout = 60, maxCount = 60)
    @PostMapping("/chat/cancel/{id}")
    public AjaxJson<?> cancel(@PathVariable String id) {
        validateId(id);
        aiAgentService.cancel(id, TlgxyCloudAuthUtil.getCurrentUserId());
        return AjaxJson.getSuccess();
    }

    private void writeStream(String id, AiChatRequest request,
                             List<im.tlgxy.cloud.module.ai.model.bo.AiAttachmentContent> attachments,
                             HttpServletResponse response) {
        validateId(id);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        int owner = TlgxyCloudAuthUtil.getCurrentUserId();
        Consumer<AiAgentService.StreamEvent> sink = event -> {
            try {
                byte[] bytes = ("event: " + event.event() + "\ndata: " + objectMapper.writeValueAsString(event.data()) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                response.getOutputStream().write(bytes);
                response.flushBuffer();
            } catch (IOException e) {
                aiAgentService.cancel(id, owner);
                throw new UncheckedIOException(e);
            }
        };
        try {
            aiAgentService.stream(id, owner, request, attachments, sink);
        } catch (UncheckedIOException ignored) {
            // Browser disconnected: upstream is closed by the session's try-with-resources.
        } catch (Exception e) {
            String message = e instanceof BizException ? e.getMessage() : "AI 对话暂时失败，请稍后重试";
            try { sink.accept(new AiAgentService.StreamEvent("error", Map.of("message", message))); }
            catch (UncheckedIOException ignored) { }
        }
    }

    private void validateId(String id) {
        try {
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new BizException("对话标识不正确");
        }
    }
}
