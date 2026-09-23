package im.tlgxy.cloud.module.user.controller;

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import dev.samstevens.totp.exceptions.QrGenerationException;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.user.model.request.VerifyLoginTwoFactorAuthenticatorRequest;
import im.tlgxy.cloud.module.user.model.result.LoginTwoFactorAuthenticatorResult;
import im.tlgxy.cloud.module.user.service.login.TwoFactorAuthenticatorVerifyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * 管理员两步验证（2FA）控制器
 * <p>
 * 提供管理员两步验证相关的 REST API 接口，包括：
 * 1. 生成 2FA 密钥和二维码，供管理员在认证器应用（如 Google Authenticator）中扫码绑定
 * 2. 验证管理员输入的 2FA 验证码并完成设备绑定
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "登录模块")
@ApiSort(1)
@RestController
@RequestMapping("/admin")
public class AdminTwoFAController {

    /**
     * 两步验证服务，处理 2FA 设备设置和验证的核心逻辑
     */
    @Resource
    private TwoFactorAuthenticatorVerifyService twoFactorAuthenticatorVerifyService;

    /**
     * 生成两步验证（2FA）密钥和二维码
     * <p>
     * 为当前管理员生成 TOTP 密钥和对应的二维码图片（Base64 编码），
     * 管理员可以使用 Google Authenticator 等认证器应用扫描二维码进行绑定。
     * </p>
     *
     * @return 包含 2FA 密钥和二维码 Base64 字符串的结果对象
     * @throws QrGenerationException 二维码生成失败时抛出
     */
    @ApiOperationSupport(order = 1)
    @Operation(summary = "生成 2FA")
    @GetMapping("/2fa/setup")
    public AjaxJson<LoginTwoFactorAuthenticatorResult> setupDevice() throws QrGenerationException {
        LoginTwoFactorAuthenticatorResult loginTwoFactorAuthenticatorResult = twoFactorAuthenticatorVerifyService.setupDevice();
        return AjaxJson.getSuccessData(loginTwoFactorAuthenticatorResult);
    }


    /**
     * 验证两步验证（2FA）验证码并完成绑定
     * <p>
     * 管理员在认证器应用中输入看到的验证码后，调用此接口进行验证。
     * 验证通过后，将该设备与管理员账号绑定，之后的登录流程将需要提供 2FA 验证码。
     * 演示模式下禁用此接口。
     * </p>
     *
     * @param verifyLoginTwoFactorAuthenticatorRequest 包含 2FA 验证码的请求对象
     * @return 操作成功的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 2)
    @Operation(summary = "2FA 验证并绑定")
    @PostMapping("/2fa/verify")
    @DemoDisable
    public AjaxJson<Void> deviceVerify(@Valid @RequestBody VerifyLoginTwoFactorAuthenticatorRequest verifyLoginTwoFactorAuthenticatorRequest) {
        twoFactorAuthenticatorVerifyService.deviceVerify(verifyLoginTwoFactorAuthenticatorRequest);
        return AjaxJson.getSuccess();
    }

}