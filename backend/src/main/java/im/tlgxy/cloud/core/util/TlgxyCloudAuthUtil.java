package im.tlgxy.cloud.core.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.extra.spring.SpringUtil;
import im.tlgxy.cloud.module.share.context.ShareAccessContext;
import im.tlgxy.cloud.core.exception.core.BizException;
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
 *     <li>用户服务在请求时从当前 Spring 容器获取，避免循环依赖和容器重建后的旧引用。</li>
 *     <li>未登录或登录状态失效时直接拒绝，不回退到历史访客账号。</li>
 * </ul>
 *
 * @author zhaojun
 */
public class TlgxyCloudAuthUtil {

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
		// 不把服务对象保存到静态字段；重建容器后必须使用当前数据源。
		UserService userService = SpringUtil.getBean(UserService.class);

        Integer backgroundActor = BackgroundActorContext.currentActor();
        if (backgroundActor != null) return userService.requireLoginUser(backgroundActor);

        // 检查是否为分享访问，如果是则返回分享者用户 ID 对应的用户信息
        Integer sessionUserId = requireSessionUserId();
        if (ShareAccessContext.isShareAccess()) {
            Integer shareUserId = ShareAccessContext.getShareUserId();
            return userService.requireLoginUser(shareUserId);
        }

        // 普通访问：通过 Sa-Token 获取当前登录用户 ID，然后查询用户信息
        return userService.requireLoginUser(sessionUserId);
	}

	/**
	 * 获取当前登录用户的 ID。
	 *
	 * <p>访问逻辑：</p>
	 * <ol>
	 *     <li>如果是分享链接访问，则返回分享者（文件所有者）的用户 ID；</li>
	 *     <li>如果是普通登录访问，则通过 Sa-Token 获取当前登录用户 ID；</li>
	 *     <li>未登录时拒绝访问，不将异常转换为访客身份。</li>
	 * </ol>
	 *
	 * @return 当前已认证用户的 ID。
	 */
	public static Integer getCurrentUserId() {
		Integer backgroundActor = BackgroundActorContext.currentActor();
		if (backgroundActor != null) return backgroundActor;
        Integer sessionUserId = requireSessionUserId();
        // 检查是否为分享访问，如果是则返回分享者用户 ID
        if (ShareAccessContext.isShareAccess()) {
            Integer owner = ShareAccessContext.getShareUserId();
            if (owner == null || owner <= 0 || owner.equals(UserConstant.ANONYMOUS_ID)) {
                throw new BizException("分享所有者无效");
            }
            return owner;
        }

        // 普通访问：通过 Sa-Token 获取当前登录用户 ID
        return sessionUserId;
	}

    private static Integer requireSessionUserId() {
        Integer id = StpUtil.getLoginIdAsInt();
        if (id <= 0 || id.equals(UserConstant.ANONYMOUS_ID)) {
            throw new BizException("必须使用有效账号登录");
        }
        return id;
    }

}
