package im.tlgxy.cloud.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户数据访问层接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基本的 CRUD 方法。
 * 同时定义了三个自定义查询方法：
 * 1. 根据用户名查找用户 ID
 * 2. 统计符合条件的用户名数量（用于查重，支持排除指定 ID）
 * 3. 根据用户 ID 更新用户名和密码
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查找用户 ID
     *
     * @param username 用户名
     * @return 对应用户名的用户 ID，如果用户不存在则返回 null
     */
    Integer findIdByUsername(@Param("username") String username);

    /**
     * 统计指定用户名的用户数量（支持排除指定 ID）
     * <p>
     * 用于用户名查重校验，在编辑用户时可以排除当前用户自身。
     * </p>
     *
     * @param username 要检查的用户名
     * @param ignoreId 需要排除的用户 ID（编辑场景下传入当前用户 ID，新增场景传入 null）
     * @return 符合条件的用户数量，大于 0 表示用户名已被占用
     */
    int countByUsername(@Param("username") String username, @Param("ignoreId") Integer ignoreId);

    /**
     * 根据用户 ID 更新用户名和密码
     *
     * @param id       用户 ID
     * @param username 新的用户名
     * @param password 新的密码（已加密）
     * @return 受影响的行数
     */
    int updateUserNameAndPwdById(@Param("id") Integer id, @Param("username") String username, @Param("password") String password);

}