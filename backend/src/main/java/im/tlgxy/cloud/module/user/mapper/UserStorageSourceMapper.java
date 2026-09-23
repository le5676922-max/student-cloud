package im.tlgxy.cloud.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.user.model.dto.UserStorageSourceDetailDTO;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户存储源权限数据访问层接口
 * <p>
 * 负责 user_storage_source（用户-存储源关联）表的数据操作。
 * 继承 MyBatis-Plus 的 BaseMapper 获得基本 CRUD 能力，同时提供：
 * 1. 按用户 ID/存储源 ID 删除权限记录
 * 2. 按用户 ID 查询带存储源详细信息的权限列表
 * 3. 按用户 ID 和存储源 ID 精确查询单个权限记录
 * 4. 按存储源 ID 或用户 ID 查询权限列表
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface UserStorageSourceMapper extends BaseMapper<UserStorageSource> {

    /**
     * 按用户 ID 删除该用户的所有存储源权限记录
     * <p>
     * 通常在删除用户或更新用户权限时调用，先删除旧权限再插入新权限。
     * </p>
     *
     * @param userId 用户 ID
     * @return 删除的记录数
     */
    int deleteByUserId(@Param("userId") Integer userId);

    /**
     * 按存储源 ID 删除所有用户对该存储源的权限记录
     * <p>
     * 通常在删除存储源时调用，清理所有用户与该存储源的关联关系。
     * </p>
     *
     * @param storageId 存储源 ID
     * @return 删除的记录数
     */
    int deleteByStorageId(@Param("storageId") Integer storageId);

    /**
     * 根据用户 ID 查询该用户的所有存储源权限详情
     * <p>
     * 返回的 DTO 列表不仅包含权限信息，还包含了对应存储源的详细信息（名称、类型等），
     * 用于前端展示用户的存储源权限配置。
     * </p>
     *
     * @param userId 用户 ID
     * @return 包含存储源详细信息的权限 DTO 列表
     */
    List<UserStorageSourceDetailDTO> getDTOListByUserId(@Param("userId") Integer userId);

    /**
     * 根据用户 ID 和存储源 ID 精确查询单个权限记录
     *
     * @param userId    用户 ID
     * @param storageId 存储源 ID
     * @return 用户的存储源权限记录，如果不存在则返回 null
     */
    UserStorageSource getByUserIdAndStorageId(@Param("userId") Integer userId, @Param("storageId") Integer storageId);

    /**
     * 根据存储源 ID 查询所有拥有该存储源权限的用户列表
     *
     * @param storageId 存储源 ID
     * @return 拥有该存储源权限的用户列表
     */
    List<UserStorageSource> selectByStorageId(@Param("storageId") Integer storageId);

    /**
     * 根据用户 ID 查询该用户的所有存储源权限记录
     *
     * @param userId 用户 ID
     * @return 用户的所有存储源权限记录列表
     */
    List<UserStorageSource> selectByUserId(@Param("userId") Integer userId);

}