package im.tlgxy.cloud.module.user.controller;

import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.user.model.request.PublicRegistrationRequest;
import im.tlgxy.cloud.module.user.model.request.RegistrationEmailCodeRequest;
import im.tlgxy.cloud.module.user.service.PublicRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class PublicRegistrationController {
    private final PublicRegistrationService service;

    @GetMapping("/registration/config")
    public AjaxJson<PublicRegistrationService.Config> config() { return AjaxJson.getSuccessData(service.config()); }

    @PostMapping("/registration/email-code")
    @ApiLimit(timeout = 600, maxCount = 10)
    @DemoDisable
    public AjaxJson<Void> emailCode(@Valid @RequestBody RegistrationEmailCodeRequest request) {
        service.sendEmailCode(request.email());
        return AjaxJson.getSuccess("验证码已发送，请检查邮箱");
    }

    @PostMapping("/register")
    @ApiLimit(timeout = 600, maxCount = 10)
    @DemoDisable
    public AjaxJson<PublicRegistrationService.Result> register(@Valid @RequestBody PublicRegistrationRequest request) {
        return AjaxJson.getSuccessData(service.register(request));
    }
}
