package io.studentcloud.intelligence.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.account.Actor;
import io.studentcloud.assistant.MemoryService;
import io.studentcloud.assistant.MemoryView;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/memories")
public class MemoryController {
    private final MemoryService memories;
    public MemoryController(MemoryService memories) { this.memories = memories; }
    public record MemoryInput(@NotBlank @Size(max=500) String content, Long expectedVersion, @Size(max=500) String expectedContent) { }
    public record SettingsInput(boolean enabled) { }
    public record Item(String id, String content, long version, long createdAt, long updatedAt) {
        static Item of(MemoryView memory) { return new Item(Long.toString(memory.id()), memory.content(), memory.version(), memory.createdAt(), memory.updatedAt()); }
    }
    public record Snapshot(boolean enabled, int maxEntries, int maxChars, List<Item> items) { }

    @GetMapping public ApiResponse<Snapshot> list(HttpServletRequest request) { return snapshot(AuthenticationFilter.actor(request)); }
    @PostMapping public ApiResponse<Snapshot> create(@Valid @RequestBody MemoryInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        if (!memories.settings(actor).enabled()) throw new ApplicationFault("41000", 409, "请先启用长期记忆");
        memories.create(actor, input.content()); return snapshot(actor);
    }
    @PutMapping("/{id}") public ApiResponse<Snapshot> update(@PathVariable long id, @Valid @RequestBody MemoryInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        if (!memories.settings(actor).enabled()) throw new ApplicationFault("41000", 409, "请先启用长期记忆");
        long version = version(actor, id, input.expectedVersion(), input.expectedContent());
        memories.update(actor, id, version, input.content()); return snapshot(actor);
    }
    @DeleteMapping("/{id}") public ApiResponse<Snapshot> delete(@PathVariable long id,
            @RequestParam(required=false) Long expectedVersion, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        memories.delete(actor, id, version(actor, id, expectedVersion, null)); return snapshot(actor);
    }
    @DeleteMapping public ApiResponse<Snapshot> clear(HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request); memories.clear(actor); return snapshot(actor);
    }
    @PutMapping("/settings") public ApiResponse<Snapshot> settings(@RequestBody SettingsInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request); memories.enable(actor, input.enabled()); return snapshot(actor);
    }

    private long version(Actor actor, long id, Long expectedVersion, String expectedContent) {
        if (expectedVersion != null) return expectedVersion;
        MemoryView current = memories.list(actor).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ApplicationFault("40400", 404, "记忆不存在"));
        if (expectedContent != null && !expectedContent.equals(current.content())) {
            throw new ApplicationFault("41000", 409, "记忆已修改，请刷新后重试");
        }
        return current.version();
    }
    private ApiResponse<Snapshot> snapshot(Actor actor) {
        return ApiResponse.success(new Snapshot(memories.settings(actor).enabled(), 50, 500,
                memories.list(actor).stream().map(Item::of).toList()));
    }
}
