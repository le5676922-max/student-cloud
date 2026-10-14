package im.tlgxy.cloud.module.user.controller;

import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.user.model.request.PasswordResetRequest;
import im.tlgxy.cloud.module.user.model.request.RegistrationEmailCodeRequest;
import im.tlgxy.cloud.module.user.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user/password-reset")
public class PasswordResetController {
    private final PasswordResetService service;

    @GetMapping("/config")
    public AjaxJson<PasswordResetService.Config> config() {
        return AjaxJson.getSuccessData(service.config());
    }

    @PostMapping("/email-code")
    @ApiLimit(timeout = 600, maxCount = 10)
    @DemoDisable
    public AjaxJson<Void> emailCode(@Valid @RequestBody RegistrationEmailCodeRequest request) {
        service.sendEmailCode(request.email());
        return AjaxJson.getSuccess(PasswordResetService.SENT_MESSAGE);
    }

    @PostMapping
    @ApiLimit(timeout = 600, maxCount = 10)
    @DemoDisable
    public AjaxJson<Void> reset(@Valid @RequestBody PasswordResetRequest request) {
        service.reset(request);
        return AjaxJson.getSuccess(PasswordResetService.RESET_MESSAGE);
    }
}
