package im.tlgxy.cloud.module.ai.filter;

import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects oversized AI multipart requests before Spring parses their files. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AiAttachmentSizeFilter extends OncePerRequestFilter {

    static final long MAX_REQUEST_BYTES = AiAttachmentService.MAX_FILES * AiAttachmentService.MAX_FILE_BYTES
            + 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !"/api/ai/chat/files".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "AI 附件请求不能超过 " + MAX_REQUEST_BYTES / 1024 / 1024 + " MB");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
