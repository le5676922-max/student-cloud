package im.tlgxy.cloud.core.config.security;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.convert.Convert;
import im.tlgxy.cloud.module.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 自定义权限加载接口实现类。
 * <p>
 * 实现了 {@link StpInterface} 接口，负责在 Sa-Token 进行权限校验时动态加载
 * 当前登录用户的<b>权限码列表</b>和<b>角色标识列表</b>。
 * </p>
 * <p>
 * 当前实现：
 * </p>
 * <ul>
 *   <li><b>getPermissionList：</b>未使用权限码功能，返回空集合。</li>
 *   <li><b>getRoleList：</b>根据用户 ID 查询数据库判断是否为管理员，是则返回 ["admin"] 角色。</li>
 * </ul>
 * <p>
 * 工作流程：当 Sa-Token 执行 {@code StpUtil.checkRole("admin")} 时，
 * 会自动调用此类的 getRoleList 方法获取当前用户的角色列表进行比对。
 * </p>
 *
 * @author zhaojun
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /**
     * 管理员角色列表（单例 List，只包含 "admin" 一个角色）
     */
    private static final List<String> ADMIN_ROLE_LIST = List.of("admin");

    /**
     * 空角色列表（用于非管理员用户）
     */
    public static final List<String> EMPTY_ROLE_LIST = List.of();

    /**
     * 用户服务，用于查询用户的权限和角色信息
     */
    @Resource
    private UserService userService;

    /**
     * 返回一个账号所拥有的权限码集合。
     * <p>
     * 当前项目中未使用 Sa-Token 的权限码功能，因此直接返回空集合。
     * 如果后续需要细粒度的权限控制（如按钮级别），可以在此方法中实现。
     * </p>
     *
     * @param loginId   登录用户 ID
     * @param loginType 登录类型（如 "login"、"phone" 等）
     * @return 权限码列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /**
     * 返回一个账号所拥有的角色标识集合（权限与角色可分开校验）。
     * <p>
     * 根据用户 ID 调用 {@link UserService#isAdmin(Integer)} 判断当前用户是否为管理员，
     * 如果是则返回包含 "admin" 的单例列表，否则返回空列表。
     * </p>
     *
     * @param loginId   登录用户 ID
     * @param loginType 登录类型
     * @return 角色标识列表，如果是管理员则返回 ["admin"]，否则返回 []
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 将 loginId 转换为 Integer，查询用户是否为管理员
        Integer userId = Convert.toInt(loginId);
        return userService.isAdmin(userId) ? ADMIN_ROLE_LIST : EMPTY_ROLE_LIST;
    }

}
