package im.tlgxy.cloud.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.ai.service.AiLongTermMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** A user can manage only their own explicitly saved long-term memory. */
@RestController
@RequestMapping("/api/ai/memories")
@SaCheckLogin
@RequiredArgsConstructor
public class AiMemoryController {
    private final AiLongTermMemoryService memory;

    public record Save(@NotBlank @Size(max = 500) String content) { }
    public record Update(@NotBlank @Size(max = 500) String content, @Size(max = 500) String expectedContent) { }
    public record Settings(@NotNull Boolean enabled) { }

    @GetMapping
    @ApiLimit(timeout = 60, maxCount = 60)
    public AjaxJson<AiLongTermMemoryService.State> state() {
        return AjaxJson.getSuccessData(memory.state(owner()));
    }

    @PostMapping
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<AiLongTermMemoryService.State> save(@Valid @RequestBody Save request) {
        return AjaxJson.getSuccessData(memory.remember(owner(), request.content()));
    }

    @PutMapping("/settings")
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<AiLongTermMemoryService.State> settings(@Valid @RequestBody Settings request) {
        return AjaxJson.getSuccessData(memory.setEnabled(owner(), request.enabled()));
    }

    @PutMapping("/{id}")
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<AiLongTermMemoryService.State> update(@PathVariable String id, @Valid @RequestBody Update request) {
        return AjaxJson.getSuccessData(memory.update(owner(), id, request.content(), request.expectedContent()));
    }

    @DeleteMapping("/{id}")
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<AiLongTermMemoryService.State> forget(@PathVariable String id) {
        return AjaxJson.getSuccessData(memory.forget(owner(), id));
    }

    @DeleteMapping
    @ApiLimit(timeout = 60, maxCount = 30)
    public AjaxJson<AiLongTermMemoryService.State> clear() {
        return AjaxJson.getSuccessData(memory.clear(owner()));
    }

    private int owner() { return TlgxyCloudAuthUtil.getCurrentUserId(); }
}
