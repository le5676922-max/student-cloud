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

/** 提供人工服务会话、消息、接待操作和通知连接票据接口。 */
@RestController
@RequestMapping("/api/support")
public class SupportController {
    private final SupportService support;
    private final SupportSocketHub sockets;
    public SupportController(SupportService support, SupportSocketHub sockets) { this.support = support; this.sockets = sockets; }
    public record MessageInput(@NotBlank @Size(max=4000) String content, @NotBlank @Size(max=128) String clientMessageId) { }

    /** 为当前登录身份申请短期人工服务通知连接票据。 */
    @PostMapping("/socket-ticket") public ApiResponse<Map<String,Object>> ticket(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(sockets.issue(AuthenticationFilter.actor(request), RequestTokens.extract(request), request.getHeader("Origin")));
    }

    /** 使用用户主动发送的消息幂等创建人工服务会话。 */
    @PostMapping("/conversations") public ApiResponse<Map<String,Object>> create(@Valid @RequestBody MessageInput input, HttpServletRequest request) {
        String subject = input.content().strip().codePoints().limit(160).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var item = support.request(AuthenticationFilter.actor(request), subject, input.content(), input.clientMessageId());
        sockets.changed(item);
        return ApiResponse.success(conversation(item));
    }
    /** 按状态分页返回当前身份可见的人工服务会话。 */
    @GetMapping("/conversations") public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) SupportState status, HttpServletRequest request) {
        if (page < 1 || page > 1000 || pageSize < 1 || pageSize > 100) throw invalid();
        var actor = AuthenticationFilter.actor(request);
        var items = support.list(actor, (page-1)*pageSize, pageSize, status).stream().map(SupportController::conversation).toList();
        return ApiResponse.success(Map.of("items", items, "total", support.count(actor, status)));
    }
    /** 返回当前身份有权访问的指定会话。 */
    @GetMapping("/conversations/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(conversation(support.get(AuthenticationFilter.actor(request), id)));
    }
    /** 按消息序号游标读取会话消息和继续加载标记。 */
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
    /** 幂等保存回复并通知相关连接刷新会话。 */
    @PostMapping("/conversations/{id}/messages") public ApiResponse<Map<String,Object>> reply(@PathVariable String id,
            @Valid @RequestBody MessageInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        var item = support.reply(actor, id, input.clientMessageId(), input.content());
        sockets.changed(support.get(actor, id));
        return ApiResponse.success(message(item));
    }
    /** 由全局管理员接待指定会话并触发变更通知。 */
    @PostMapping("/conversations/{id}/accept") public ApiResponse<Map<String,Object>> accept(@PathVariable String id, HttpServletRequest request) {
        var item = support.accept(AuthenticationFilter.actor(request), id);
        sockets.changed(item);
        return ApiResponse.success(conversation(item));
    }
    /** 按申请人或接待管理员权限结束会话并触发通知。 */
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
