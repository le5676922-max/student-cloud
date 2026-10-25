package im.tlgxy.cloud.module.ai.feedback;

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
@RequestMapping("/api/ai/feedback")
@SaCheckLogin
@RequiredArgsConstructor
public class AiFeedbackController {
    private final AiFeedbackService feedback;
    public record Submit(@NotBlank @Size(max=80) String clientMessageId,
                         @NotBlank @Size(max=2000) String question,
                         @NotBlank @Size(max=12000) String answer,
                         @Size(max=500) String pagePath, @Size(max=32) String courseId) { }

    @PostMapping
    @ApiLimit(timeout=60, maxCount=20)
    public AjaxJson<AiFeedbackService.Submission> submit(@Valid @RequestBody Submit request) {
        return AjaxJson.getSuccessData(feedback.submit(TlgxyCloudAuthUtil.getCurrentUserId(), request.clientMessageId(),
                request.question(), request.answer(), request.pagePath(), request.courseId()));
    }
}
