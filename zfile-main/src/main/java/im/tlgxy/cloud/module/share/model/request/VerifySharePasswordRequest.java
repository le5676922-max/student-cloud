package im.tlgxy.cloud.module.share.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 验证分享密码请求对象
 * <p>
 * 用于接收访问者提交的分享密码验证请求。当分享链接设置了密码保护时，
 * 访问者需要先通过此接口验证密码，验证通过后才能访问分享内容。
 * 包含分享链接的唯一标识以及访问者输入的密码。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "验证分享密码请求")
public class VerifySharePasswordRequest {

    /**
     * 分享链接的唯一标识 key，用于定位需要验证密码的分享记录
     */
    @Schema(title = "分享链接 key", requiredMode = Schema.RequiredMode.REQUIRED, example = "abc12345")
    @NotBlank(message = "分享链接 key 不能为空")
    private String shareKey;

    /**
     * 访问者输入的分享密码，用于与分享记录中存储的密码进行比对验证
     */
    @Schema(title = "分享密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    @NotBlank(message = "分享密码不能为空")
    private String password;
}
