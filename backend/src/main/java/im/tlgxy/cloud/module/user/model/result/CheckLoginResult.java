package im.tlgxy.cloud.module.user.model.result;

import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 检查登录状态结果类
 * <p>
 * 用于返回当前用户登录状态的检查结果。前端通过调用登录状态检查接口，
 * 获取此对象来判断用户是否已登录、是否为管理员以及用户名等信息。
 * 实现 Serializable 接口以支持序列化（如存入分布式 Session 时）。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class CheckLoginResult implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 是否已登录
     * <p>true 表示当前用户已登录，false 表示未登录或会话已过期。</p>
     */
    private Boolean isLogin;

    /**
     * 是否为管理员
     * <p>true 表示当前登录用户拥有管理员权限。</p>
     */
    private Boolean isAdmin;

    /**
     * 用户名（登录账号）
     */
    private String username;

    /**
     * 用户昵称（显示名称）
     */
    private String nickname;

    private UserRoleEnum role;

    private Integer tenantId;

}
