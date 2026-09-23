package im.tlgxy.cloud.core.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.service.UserService;

/**
 * TlgxyCloud 登录认证工具类，用于获取当前登录用户的信息。
 *
 * <p>该类是 TlgxyCloud 系统中获取当前操作用户的统一入口，支持以下两种访问场景：</p>
 * <ul>
 *     <li><b>普通登录访问</b>：通过 Sa-Token 框架获取当前登录会话中的用户 ID，再查询完整的用户信息。</li>
 *     <li><b>分享链接访问</b>：通过 {@link ShareAccessContext} 判断当前请求是否为分享链接访问，
 *         如果是则返回分享者（而非当前访问者）的用户信息。</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li>{@link #userService} 通过懒加载方式从 Spring 容器中获取，避免循环依赖问题。</li>
 *     <li>登录失败或未登录时会返回匿名用户 ID（{@code UserConstant.ANONYMOUS_ID}），
 *         保证系统在未认证状态下也能正常运转。</li>
 * </ul>
 *
 * @author zhaojun
 */
public class TlgxyCloudAuthUtil {

	/**
	 * 用户服务实例，通过懒加载方式从 Spring 容器中获取。
	 * 不使用 @Resource 注入是为了避免在某些初始化阶段产生循环依赖。
	 */
	private static UserService userService;

	/**
	 * 获取当前登录用户的完整信息。
	 *
	 * <p>访问逻辑：</p>
	 * <ol>
	 *     <li>如果是分享链接访问，则返回分享者（文件所有者）的用户信息；</li>
	 *     <li>如果是普通登录访问，则通过 Sa-Token 获取当前登录用户 ID，
	 *         再查询完整用户信息返回。</li>
	 * </ol>
	 *
	 * @return  当前登录用户实体 {@link User}，如果获取失败则可能返回 null。
	 */
	public static User getCurrentUser() {
		// 懒加载获取 UserService Bean，避免循环依赖
		if (userService == null) {
			userService = SpringUtil.getBean(UserService.class);
		}

        // 检查是否为分享访问，如果是则返回分享者用户 ID 对应的用户信息
        if (ShareAccessContext.isShareAccess()) {
            Integer shareUserId = ShareAccessContext.getShareUserId();
            return userService.getById(shareUserId);
        }

        // 普通访问：通过 Sa-Token 获取当前登录用户 ID，然后查询用户信息
        return userService.getById(StpUtil.getLoginId(UserConstant.ANONYMOUS_ID));
	}

	/**
	 * 获取当前登录用户的 ID。
	 *
	 * <p>访问逻辑：</p>
	 * <ol>
	 *     <li>如果是分享链接访问，则返回分享者（文件所有者）的用户 ID；</li>
	 *     <li>如果是普通登录访问，则通过 Sa-Token 获取当前登录用户 ID；</li>
	 *     <li>如果获取过程中出现任何异常（如未登录），则返回匿名用户 ID。</li>
	 * </ol>
	 *
	 * @return  当前用户的 ID；未登录或异常时返回 {@code UserConstant.ANONYMOUS_ID}。
	 */
	public static Integer getCurrentUserId() {
		// 懒加载获取 UserService Bean，避免循环依赖
		if (userService == null) {
			userService = SpringUtil.getBean(UserService.class);
		}

        // 检查是否为分享访问，如果是则返回分享者用户 ID
        if (ShareAccessContext.isShareAccess()) {
            return ShareAccessContext.getShareUserId();
        }

        // 普通访问：通过 Sa-Token 获取当前登录用户 ID
        try {
			return StpUtil.getLoginId(UserConstant.ANONYMOUS_ID);
		} catch (Exception e) {
			// 获取登录 ID 失败（如未登录），返回匿名用户 ID 作为兜底
			return UserConstant.ANONYMOUS_ID;
		}
	}

}