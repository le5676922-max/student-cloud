package im.tlgxy.cloud.module.install.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统初始化请求参数类
 * <p>
 * 封装系统首次安装时前端提交的初始化数据，包括站点基本信息和超级管理员账户信息。
 * 使用 Lombok {@link Data} 注解自动生成 getter/setter/toString 等方法。
 *
 * @author zhaojun
 */
@Data
@Schema(description = "系统初始化请求类")
public class InstallSystemRequest {

    /**
     * 站点名称，用于设置系统网站的标题
     */
    @Schema(title = "站点名称", example = "TlgxyCloud Site Name")
    private String siteName;

    /**
     * 超级管理员用户名，用于登录后台管理系统
     */
    @Schema(title = "用户名", example = "admin")
    @NotBlank(message = "管理员用户名不能为空")
    @Size(max = 100, message = "管理员用户名不能超过 100 个字符")
    private String username;

    /**
     * 超级管理员密码，用于登录后台管理系统
     */
    @Schema(title = "密码", example = "123456")
    @NotBlank(message = "管理员密码不能为空")
    @Size(min = 6, max = 128, message = "管理员密码长度必须为 6 至 128 个字符")
    private String password;

}
