package io.studentcloud.intelligence.http;

import io.studentcloud.assistant.documents.DocumentResearchService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** New bounded document contract; existing /api/ai/chat is deliberately unchanged. */
@RestController
@RequestMapping("/api/ai/documents")
public final class DocumentResearchController {
    private final DocumentResearchService research;
    public DocumentResearchController(DocumentResearchService research) {this.research=research;}
    public record Capabilities(String mode,boolean sourceVersionSupport,boolean externalEmbeddingEnabled,String reason) { }
    @GetMapping("/capabilities")
    public ApiResponse<Capabilities> capabilities(HttpServletRequest request) {
        AuthenticationFilter.actor(request);
        return ApiResponse.success(new Capabilities("LEXICAL",false,false,"DOCUMENT_FACTS_UNAVAILABLE"));
    }
    @PostMapping("/search")
    public ApiResponse<DocumentResearchService.Response> search(@RequestBody DocumentResearchService.Request input,HttpServletRequest request) {
        return ApiResponse.success(research.search(AuthenticationFilter.actor(request),input));
    }
    @PostMapping("/answer")
    public ApiResponse<DocumentResearchService.Response> answer(@RequestBody DocumentResearchService.Request input,HttpServletRequest request) {
        return ApiResponse.success(research.answer(AuthenticationFilter.actor(request),input));
    }
}
