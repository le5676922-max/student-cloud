package im.tlgxy.cloud.module.user.event;

import im.tlgxy.cloud.module.user.model.entity.User;
import lombok.Data;

/**
 * 用户删除事件
 * <p>
 * 当管理员删除某个用户时触发此事件。事件中携带被删除用户的 ID 和用户名，
 * 用于通知其他模块进行相应的清理工作（如删除该用户关联的存储源权限配置等）。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class UserDeleteEvent {

    /**
     * 被删除的用户 ID
     */
    private Integer id;

    /**
     * 被删除的用户名
     */
    private String username;

    /**
     * 构造用户删除事件
     *
     * @param user 被删除的用户实体对象
     */
    public UserDeleteEvent(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
    }

}