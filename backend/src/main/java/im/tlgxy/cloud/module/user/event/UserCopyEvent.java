package im.tlgxy.cloud.module.user.event;

import lombok.Data;

/**
 * 用户复制事件
 * <p>
 * 当管理员复制某个用户时触发此事件。事件中携带源用户 ID 和新创建用户 ID，
 * 用于通知其他模块（如存储源模块）进行相应的数据复制操作，
 * 确保新用户拥有与源用户相同的存储源权限配置。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class UserCopyEvent {

    /**
     * 被复制的源用户 ID
     */
    private Integer fromId;

    /**
     * 复制后新创建的用户 ID
     */
    private Integer newId;

    /**
     * 构造用户复制事件
     *
     * @param fromId 源用户 ID
     * @param newId  新创建的用户 ID
     */
    public UserCopyEvent(Integer fromId, Integer newId) {
        this.fromId = fromId;
        this.newId = newId;
    }

}