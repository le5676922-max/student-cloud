package im.tlgxy.cloud.module.user.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 验证登录两步验证（2FA）请求参数类
 * <p>
 * 封装验证两步验证（双因子认证）时提交的请求数据，包括两步验证密钥和 APP 生成的动态验证码。
 * 使用 @AllArgsConstructor 生成全参构造函数，方便快速构建请求对象。
 * 两种常见使用场景：
 * <ul>
 *   <li>首次绑定：用户扫描二维码后提交验证码确认绑定</li>
 *   <li>登录验证：用户登录时提交两步验证码进行二次身份确认</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
@Schema(description = "验证二步验证结果")
public class VerifyLoginTwoFactorAuthenticatorRequest {

    /**
     * 两步验证密钥（Base32 编码）
     * <p>用于生成两步验证码的共享密钥，通常以二维码形式展示给用户扫描。
     * 不能为空。</p>
     */
    @Schema(title = "二步验证二维码", requiredMode = Schema.RequiredMode.REQUIRED, example = "EwBoxxxxxxxxxxxxxxxbAI=")
    @NotBlank(message = "二步验证密钥不能为空")
    private String secret;

    /**
     * APP 生成的两步验证动态验证码
     * <p>用户在 Google Authenticator 等认证 APP 中看到的 6 位动态数字验证码。
     * 不能为空。</p>
     */
    @Schema(title = "APP 生成的二步验证验证码", requiredMode = Schema.RequiredMode.REQUIRED, example = "125612")
    @NotBlank(message = "二步验证验证码不能为空")
    private String code;

}