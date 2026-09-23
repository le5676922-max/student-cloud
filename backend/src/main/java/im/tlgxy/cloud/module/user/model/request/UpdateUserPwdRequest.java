package im.tlgxy.cloud.module.user.model.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新用户密码请求参数类
 * <p>
 * 封装用户修改密码时提交的请求数据，包括旧密码、新密码和确认密码。
 * 使用 JSR-303 校验注解确保新密码和确认密码不能为空。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class UpdateUserPwdRequest {

    /**
     * 旧密码（原密码）
     * <p>修改密码时需要提供原密码进行身份验证，非必填字段。</p>
     */
    private String oldPassword;

    /**
     * 新密码
     * <p>不能为空，用于替换旧密码。</p>
     */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;

    /**
     * 确认密码
     * <p>不能为空，需与新密码一致，用于防止用户输入错误。</p>
     */
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

}