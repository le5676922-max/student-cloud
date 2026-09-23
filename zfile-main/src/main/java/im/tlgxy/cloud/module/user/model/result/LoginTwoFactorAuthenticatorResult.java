package im.tlgxy.cloud.module.user.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录两步验证（2FA）认证生成结果类
 * <p>
 * 生成两步验证（双因子认证/2FA）配置信息时返回的结果对象。
 * 包含二维码内容（Base64 编码）和密钥字符串。
 * 前端将 qrcode 渲染为二维码图片供用户使用 Google Authenticator 等 APP 扫描绑定，
 * secret 用于服务端后续校验动态验证码。
 * 使用 @AllArgsConstructor 生成全参构造函数，实现 Serializable 以支持序列化。
 * </p>
 *
 * @author zhaojun
 */
@Data
@AllArgsConstructor
@Schema(description = "生成二步验证结果")
public class LoginTwoFactorAuthenticatorResult implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 两步验证二维码内容（Base64 编码字符串）
     * <p>通常为 otpauth:// 协议的 URI 字符串经 Base64 编码后的二维码图片数据，
     * 前端直接将其渲染为二维码图片供用户扫描。</p>
     */
    @Schema(title = "二步验证二维码")
    private String qrcode;

    /**
     * 两步验证密钥（Base32 编码）
     * <p>用于生成动态验证码的共享密钥，服务端保存此密钥用于校验用户提交的验证码。
     * 用户也可手动输入此密钥到认证 APP 中完成绑定。</p>
     */
    @Schema(title = "二步验证密钥")
    private String secret;

}