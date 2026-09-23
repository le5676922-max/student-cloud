package im.tlgxy.cloud.module.user.model.constant;

/**
 * 用户模块常量类
 * <p>
 * 定义系统中预设的特殊用户 ID 常量，这些 ID 在系统初始化时创建，具有特殊含义：
 * 1. NEW_USER_TEMPLATE_ID (0)：新用户模板 ID，用于为新注册用户提供默认配置模板
 * 2. ADMIN_ID (1)：内置管理员用户 ID，具有最高系统权限
 * 3. ANONYMOUS_ID (2)：匿名用户 ID，用于未登录访问时的权限控制
 * </p>
 *
 * @author zhaojun
 */
public class UserConstant {

    public static final Integer DEFAULT_TENANT_ID = 1;

    /**
     * 新用户模板 ID
     * <p>
     * ID 为 0 的用户作为新用户模板，当创建新用户时，会参考此模板的配置
     * 来设置新用户的默认存储源权限。
     * </p>
     */
    public static final Integer NEW_USER_TEMPLATE_ID = 0;

    /**
     * 内置管理员用户 ID
     * <p>
     * 系统初始化时创建的管理员账号，固定的 ID 为 1，拥有系统的所有管理权限。
     * </p>
     */
    public static final Integer ADMIN_ID = 1;

    /**
     * 匿名用户 ID
     * <p>
     * ID 为 2 的用户代表匿名/未登录用户，用于控制未登录状态下对存储源的访问权限。
     * </p>
     */
    public static final Integer ANONYMOUS_ID = 2;

}
