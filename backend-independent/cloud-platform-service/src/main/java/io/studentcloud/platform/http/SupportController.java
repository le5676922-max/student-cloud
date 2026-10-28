package io.studentcloud.platform.http;

import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.RequestTokens;

import io.studentcloud.account.Role;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.platform.support.SupportSocketHub;
import io.studentcloud.support.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportService support;
    private final SupportSocketHub sockets;
    public SupportController(SupportService support, SupportSocketHub sockets) { this.support = support; this.sockets = sockets; }
    public record MessageInput(@NotBlank @Size(max=4000) String content, @NotBlank @Size(max=128) String clientMessageId) { }

    @PostMapping("/socket-ticket") public ApiResponse<Map<String,Object>> ticket(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(sockets.issue(AuthenticationFilter.actor(request), RequestTokens.extract(request), request.getHeader("Origin")));
    }

    @PostMapping("/conversations") public ApiResponse<Map<String,Object>> create(@Valid @RequestBody MessageInput input, HttpServletRequest request) {
        String subject = input.content().strip().codePoints().limit(160).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var item = support.request(AuthenticationFilter.actor(request), subject, input.content(), input.clientMessageId());
        sockets.changed(item);
        return ApiResponse.success(conversation(item));
    }
    @GetMapping("/conversations") public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) SupportState status, HttpServletRequest request) {
        if (page < 1 || page > 1000 || pageSize < 1 || pageSize > 100) throw invalid();
        var actor = AuthenticationFilter.actor(request);
        var items = support.list(actor, (page-1)*pageSize, pageSize, status).stream().map(SupportController::conversation).toList();
        return ApiResponse.success(Map.of("items", items, "total", support.count(actor, status)));
    }
    @GetMapping("/conversations/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(conversation(support.get(AuthenticationFilter.actor(request), id)));
    }
    @GetMapping("/conversations/{id}/messages") public ApiResponse<Map<String,Object>> messages(@PathVariable String id,
            @RequestParam(required=false) Long beforeId, @RequestParam(required=false) Long afterId,
            @RequestParam(defaultValue="100") int limit, HttpServletRequest request) {
        if (limit < 1 || limit > 100 || (beforeId != null && afterId != null)
                || beforeId != null && beforeId < 1 || afterId != null && afterId < 0) throw invalid();
        var actor = AuthenticationFilter.actor(request);
        long total = support.messageCount(actor, id);
        long end = beforeId != null ? Math.min(total, beforeId-1) : total;
        long offset = afterId != null ? Math.min(total, afterId) : Math.max(0, end-limit);
        if (offset > 100_000) throw invalid();
        int size = afterId != null ? limit : (int)Math.min(limit, Math.max(1, end-offset));
        List<Map<String,Object>> items = end == 0 ? List.of() : support.messages(actor, id, (int)offset, size).stream().map(SupportController::message).toList();
        boolean more = afterId != null ? offset + items.size() < total : offset > 0;
        return ApiResponse.success(Map.of("items", items, "hasMore", more, "total", total));
    }
    @PostMapping("/conversations/{id}/messages") public ApiResponse<Map<String,Object>> reply(@PathVariable String id,
            @Valid @RequestBody MessageInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        var item = support.reply(actor, id, input.clientMessageId(), input.content());
        sockets.changed(support.get(actor, id));
        return ApiResponse.success(message(item));
    }
    @PostMapping("/conversations/{id}/accept") public ApiResponse<Map<String,Object>> accept(@PathVariable String id, HttpServletRequest request) {
        var item = support.accept(AuthenticationFilter.actor(request), id);
        sockets.changed(item);
        return ApiResponse.success(conversation(item));
    }
    @PostMapping("/conversations/{id}/close") public ApiResponse<Map<String,Object>> close(@PathVariable String id, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        var item = actor.role() == Role.ADMIN ? support.close(actor, id) : support.closeForRequester(actor, id);
        sockets.changed(item);
        return ApiResponse.success(conversation(item));
    }

    private static Map<String,Object> conversation(SupportConversation item) {
        var result = new LinkedHashMap<String,Object>();
        result.put("id", item.id()); result.put("subject", item.subject()); result.put("status", item.state().name());
        result.put("requesterId", item.requesterId()); result.put("assignedAdminId", item.assignedAdminId());
        result.put("createdAt", item.createdAt()); result.put("updatedAt", item.updatedAt()); result.put("closedAt", item.closedAt()); return result;
    }
    private static Map<String,Object> message(SupportMessage item) {
        return Map.of("id", item.messageNo(), "conversationId", item.conversationId(), "senderId", item.senderId(),
                "senderRole", item.senderRole() == Role.ADMIN ? "ADMIN" : "USER", "clientMessageId", item.clientMessageId(),
                "content", item.body(), "createdAt", item.createdAt());
    }
    private static ApplicationFault invalid() { return new ApplicationFault("41000", 400, "客服分页参数无效"); }
}
