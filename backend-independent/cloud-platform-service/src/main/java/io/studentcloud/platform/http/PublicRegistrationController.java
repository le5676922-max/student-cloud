package io.studentcloud.platform.http;

import io.studentcloud.account.PublicRegistrationService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicRegistrationController {
    private final PublicRegistrationService registrations;
    public PublicRegistrationController(PublicRegistrationService registrations){this.registrations=registrations;}
    public record EmailInput(@NotBlank @Size(max=254) String email,@Size(max=30) String purpose){ }
    public record RegisterInput(@NotBlank @Size(max=254) String email,@NotBlank @Size(min=6,max=6) String emailCode,
            @NotBlank @Size(min=8,max=128) String password,@NotBlank @Size(min=8,max=128) String confirmPassword,
            @Size(max=100) String nickname,@NotBlank @Size(max=30) String registrationType,
            @Size(max=80) String workspaceName,@Size(max=10) String joinType,@Size(max=80) String joinValue){ }
    public record ResetInput(@NotBlank @Size(max=254) String email,@NotBlank @Size(min=6,max=6) String emailCode,
            @NotBlank @Size(min=8,max=128) String password,@NotBlank @Size(min=8,max=128) String confirmPassword){ }

    @GetMapping("/user/registration/config") public ApiResponse<Map<String,Object>> registrationConfig(){
        return ApiResponse.success(Map.of("enabled",registrations.registrationAvailable(),"mailAvailable",registrations.mailAvailable(),
                "message",registrations.registrationAvailable()?"支持邮箱验证注册，空间管理员无需审批":"注册暂不可用，请检查邮件服务配置"));
    }
    @GetMapping("/user/password-reset/config") public ApiResponse<Map<String,Object>> resetConfig(){
        return ApiResponse.success(Map.of("enabled",registrations.resetAvailable(),"mailAvailable",registrations.mailAvailable(),
                "message",registrations.resetAvailable()?"通过注册邮箱找回密码":"找回密码暂不可用，请联系管理员"));
    }
    @PostMapping("/user/registration/email-code") public ApiResponse<Void> registrationCode(@Valid @RequestBody EmailInput input){
        if(input.purpose()!=null && !"REGISTER".equals(input.purpose())) throw new ApplicationFault("41000",400,"验证码用途不正确");
        registrations.requestRegistrationCode(input.email());return success("验证码已发送，请查收邮箱");
    }
    @PostMapping("/user/password-reset/email-code") public ApiResponse<Void> resetCode(@Valid @RequestBody EmailInput input){
        registrations.requestResetCode(input.email());return success("如果该邮箱已注册，将收到验证码，请检查收件箱");
    }
    @PostMapping("/user/register") public ApiResponse<PublicRegistrationService.RegistrationResult> register(@Valid @RequestBody RegisterInput input){
        var saved=registrations.register(new PublicRegistrationService.RegistrationInput(input.email(),input.emailCode(),input.password(),input.confirmPassword(),
                input.nickname(),input.registrationType(),input.workspaceName(),input.joinType(),input.joinValue()));
        return new ApiResponse<>("0",saved.message(),saved,null,null);
    }
    @PostMapping("/user/password-reset") public ApiResponse<Void> reset(@Valid @RequestBody ResetInput input){
        registrations.resetPassword(input.email(),input.emailCode(),input.password(),input.confirmPassword());
        return success("密码已重置，原登录状态已失效，请重新登录");
    }
    private static ApiResponse<Void> success(String message){return new ApiResponse<>("0",message,null,null,null);}
}
