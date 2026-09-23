package im.tlgxy.cloud.module.config.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改用户名和密码的请求参数类。
 * <p>
 * 用于后台管理页面修改管理员登录的用户名和密码，两项均为必填。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "用户修改密码请求参数类")
public class UpdateUserNameAndPasswordRequest {

    /**
     * 管理员用户名（必填）
     */
    @Schema(title = "用户名", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin")
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 管理员密码（必填）
     */
    @Schema(title = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;

}
