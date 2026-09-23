package im.tlgxy.cloud.module.user.model.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 生成图片验证码结果类
 * <p>
 * 请求图形验证码时返回的结果对象。
 * 包含 Base64 编码的验证码图片数据和一个唯一标识 UUID。
 * 前端将 imgBase64 渲染为图片展示给用户，登录时需将用户输入的验证码和 UUID 一起提交，
 * 服务端通过 UUID 找到对应的正确答案进行比对校验。
 * 实现 Serializable 接口以支持序列化。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "生成图片验证码结果类")
public class LoginVerifyImgResult implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 验证码图片（Base64 编码的 Data URI）
     * <p>格式为 "data:image/png;base64,xxxx"，前端可直接设置为 img 标签的 src 属性进行展示。</p>
     */
    @Schema(title = "验证码图片", example = "data:image/png;base64,iajsiAAA...")
    private String imgBase64;

    /**
     * 验证码唯一标识 UUID
     * <p>每个验证码图片对应一个唯一的 UUID，服务端以此作为键存储验证码正确答案。
     * 用户登录时需将此 UUID 连同用户输入的验证码一起提交。</p>
     */
    @Schema(title = "验证码 UUID", example = "c140a792-4ca2-4dac-8d4c-35750b78524f")
    private String uuid;

}