package im.tlgxy.cloud.module.support;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support/conversations")
@SaCheckLogin
@RequiredArgsConstructor
public class SupportController {
    private final SupportService support;
    public record MessageRequest(@NotBlank @Size(max = 2000) String content,
                                 @NotBlank @Size(max = 80) String clientMessageId) { }

    @GetMapping
    @ApiLimit(timeout = 60, maxCount = 120)
    public AjaxJson<SupportService.Page> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status) {
        return AjaxJson.getSuccessData(support.list(actor(), page, pageSize, status));
    }
    @PostMapping
    @ApiLimit(timeout = 60, maxCount = 20)
    public AjaxJson<SupportService.ConversationView> open(@Valid @RequestBody MessageRequest request) {
        return AjaxJson.getSuccessData(support.open(actor(), request.content(), request.clientMessageId()));
    }
    @GetMapping("/{id}")
    @ApiLimit(timeout = 60, maxCount = 120)
    public AjaxJson<SupportService.ConversationView> get(@PathVariable String id) {
        return AjaxJson.getSuccessData(support.get(actor(), id));
    }
    @GetMapping("/{id}/messages")
    @ApiLimit(timeout = 60, maxCount = 180)
    public AjaxJson<SupportService.MessagesPage> messages(@PathVariable String id,
            @RequestParam(required = false) Long afterId, @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        return AjaxJson.getSuccessData(support.messages(actor(), id, afterId, beforeId, limit));
    }
    @PostMapping("/{id}/messages")
    @ApiLimit(timeout = 60, maxCount = 60)
    public AjaxJson<SupportService.MessageView> send(@PathVariable String id, @Valid @RequestBody MessageRequest request) {
        return AjaxJson.getSuccessData(support.send(actor(), id, request.content(), request.clientMessageId()));
    }
    @PostMapping("/{id}/accept")
    @ApiLimit(timeout = 60, maxCount = 60)
    public AjaxJson<SupportService.ConversationView> accept(@PathVariable String id) {
        return AjaxJson.getSuccessData(support.accept(actor(), id));
    }
    @PostMapping("/{id}/close")
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<SupportService.ConversationView> close(@PathVariable String id) {
        return AjaxJson.getSuccessData(support.close(actor(), id));
    }
    private int actor() { return TlgxyCloudAuthUtil.getCurrentUserId(); }
}
