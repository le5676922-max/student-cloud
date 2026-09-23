package im.tlgxy.cloud.module.user.model.result;

import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录结果类
 * <p>
 * 登录成功后返回的结果对象，包含认证令牌（token）和是否为管理员的标识。
 * 前端拿到 token 后需在后续请求中携带此 token 以维持登录会话。
 * 实现 Serializable 接口以支持序列化。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class LoginResult implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 认证令牌（token）
     * <p>登录成功后生成的会话凭证，前端需在后续请求中携带此 token 进行身份认证。</p>
     */
    private String token;

    /**
     * 是否为管理员
     * <p>true 表示登录用户拥有管理员权限，前端可根据此字段控制管理功能的显示。</p>
     */
    private boolean admin;

    private UserRoleEnum role;

    private Integer tenantId;

    /**
     * 构造登录结果对象
     *
     * @param tokenValue 认证令牌值
     * @param isAdmin    是否为管理员
     */
    public LoginResult(String tokenValue, boolean isAdmin) {
        this.token = tokenValue;
        this.admin = isAdmin;
    }

    public LoginResult(String tokenValue, User user) {
        this.token = tokenValue;
        this.role = user.getRole();
        this.admin = user.getRole() != null && user.getRole().isAdmin();
        this.tenantId = user.getTenantId();
    }
}
