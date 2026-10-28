package io.studentcloud.intelligence.http;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.ChatService;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai/chat")
public final class ChatController {
    private final ChatService chat;
    private final OpenAiChatClient model;
    private final io.studentcloud.intelligence.ai.AttachmentClient attachments;
    private final ConcurrentHashMap<String, Running> running = new ConcurrentHashMap<>();
    public ChatController(ChatService chat, OpenAiChatClient model,io.studentcloud.intelligence.ai.AttachmentClient attachments) { this.chat=chat; this.model=model;this.attachments=attachments; }

    @PostMapping
    public ApiResponse<ChatService.Reply> answer(@RequestBody ChatService.Request input, HttpServletRequest request) {
        return ApiResponse.success(chat.answer(AuthenticationFilter.actor(request), input));
    }

    @PostMapping(value="/stream/{id}", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestBody ChatService.Request input, HttpServletRequest request) {
        return begin(id,input,request,null);
    }
    @PostMapping(value="/files/stream/{id}",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter filesStream(@PathVariable String id,@RequestPart("request") ChatService.Request input,
            @RequestPart("files") java.util.List<org.springframework.web.multipart.MultipartFile> files,HttpServletRequest request){
        return begin(id,input,request,attachments.extract(files));
    }
    @PostMapping("/files") public ApiResponse<ChatService.Reply> files(@RequestPart("request") ChatService.Request input,
            @RequestPart("files") java.util.List<org.springframework.web.multipart.MultipartFile> files,HttpServletRequest request){
        return ApiResponse.success(chat.answer(AuthenticationFilter.actor(request),input,attachments.extract(files)));
    }
    private synchronized SseEmitter begin(String id,ChatService.Request input,HttpServletRequest request,String attachmentText){
        validateId(id);
        Actor actor = AuthenticationFilter.actor(request);
        if(running.size()>=8 || running.values().stream().filter(task->task.actorId==actor.id()).count()>=2)
            throw new ApplicationFault("CHAT_BUSY",429,"正在处理的对话较多，请稍后重试");
        SseEmitter emitter = new SseEmitter(60_000L);
        Thread worker = Thread.ofVirtual().name("assistant-chat-" + id).unstarted(() -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "model", "message", "正在生成回答")));
                ChatService.Reply reply = chat.answer(actor, input,attachmentText);
                if (Thread.currentThread().isInterrupted()) return;
                emitter.send(SseEmitter.event().name("token").data(Map.of("text", reply.answer())));
                emitter.send(SseEmitter.event().name("done").data(reply));
                emitter.complete();
            } catch (ApplicationFault fault) {
                sendError(emitter, fault.getMessage());
            } catch (IOException failure) {
                emitter.completeWithError(failure);
            } catch (RuntimeException failure) {
                sendError(emitter,"本轮回答未完成，请稍后重试");
            } finally { running.remove(id,new Running(actor.id(),Thread.currentThread())); }
        });
        Running reservation=new Running(actor.id(),worker);
        Running previous = running.putIfAbsent(id,reservation);
        if (previous != null) throw new ApplicationFault("CHAT_DUPLICATE", 409, "本轮对话已经开始");
        emitter.onCompletion(() -> {running.remove(id,reservation);worker.interrupt();});
        emitter.onTimeout(() -> {running.remove(id,reservation);worker.interrupt();});
        emitter.onError(failure -> {running.remove(id,reservation);worker.interrupt();});
        worker.start();
        return emitter;
    }

    @PostMapping("/cancel/{id}")
    public ApiResponse<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        validateId(id);
        Running task = running.get(id);
        if (task != null && task.actorId == AuthenticationFilter.actor(request).id()) task.thread.interrupt();
        return ApiResponse.success(null);
    }

    private static void sendError(SseEmitter emitter, String message) {
        try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", message))); emitter.complete(); }
        catch (IOException ignored) { emitter.complete(); }
    }
    private static void validateId(String id) {
        try { UUID.fromString(id); } catch (RuntimeException invalid) { throw new ApplicationFault("41000", 400, "对话标识无效"); }
    }
    private record Running(long actorId, Thread thread) { }
}
