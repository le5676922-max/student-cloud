package im.tlgxy.cloud.module.user.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户登录请求参数类
 * <p>
 * 封装用户登录时提交的请求数据，包括用户名、密码、图形验证码及其唯一标识。
 * 使用 JSR-303 校验注解确保用户名和密码不能为空。
 * 使用 Swagger 注解生成 API 文档。
 * </p>
 *
 * @author zhaojun
 */
@Data
@Schema(description = "用户登录请求参数类")
public class UserLoginRequest {

    /**
     * 用户名
     * <p>登录账号，不能为空。</p>
     */
    @Schema(title = "用户名", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin")
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码
     * <p>登录密码，不能为空。</p>
     */
    @Schema(title = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 图形验证码
     * <p>用户输入的图形验证码内容，非必填字段。</p>
     */
    @Schema(title = "验证码", example = "123456")
    private String verifyCode;

    /**
     * 验证码 UUID（唯一标识）
     * <p>用于关联图形验证码请求与验证码图片，每个验证码图片请求对应一个唯一值，
     * 服务端通过此 UUID 查找对应的验证码答案进行校验。</p>
     */
    @Schema(title = "验证码 UUID", description ="用于图形验证码确认每个验证码图片请求的唯一值.", example = "c140a792-4ca2-4dac-8d4c-35750b78524f")
    private String verifyCodeUUID;

}