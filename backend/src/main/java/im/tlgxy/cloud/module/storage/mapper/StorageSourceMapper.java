package im.tlgxy.cloud.module.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存储源基本配置 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 {@link BaseMapper}，拥有基础的 CRUD 方法。
 * 同时定义了与存储源管理相关的自定义 SQL 查询和更新操作，
 * 包括按类型查询、按 key 查询、排序值更新等。这些方法在对应的 XML 映射文件中实现。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface StorageSourceMapper extends BaseMapper<StorageSource> {

    /**
     * 获取指定用户有权限访问且已启用的存储源列表，按照排序值升序排列
     * <p>
     * 用于前端浏览页面展示时，只返回当前用户有权访问且处于启用状态的存储源。
     * 如果未启用用户权限控制，则返回所有已启用的存储源。
     * </p>
     *
     * @param   userId
     *          用户 ID，用于权限过滤
     *
     * @return  已启用的存储源列表，按排序值升序
     */
    List<StorageSource> findUserEnableList(Integer userId);


    /**
     * 获取所有存储源列表，按照排序值升序排列
     * <p>
     * 用于后台管理页面展示所有存储源（包括已禁用和未启用的），
     * 管理员可以在此查看和编辑所有存储源的配置。
     * </p>
     *
     * @return  全部存储源列表，按排序值升序
     */
    List<StorageSource> findAllOrderByOrderNum();


    /**
     * 获取存储源表中 ID 的最大值
     * <p>
     * 用于新增存储源时确定下一个可用的 ID 值。
     * </p>
     *
     * @return  存储源 ID 最大值，如果表中无记录则返回 null
     */
    Integer selectMaxId();


    /**
     * 根据存储源类型获取存储源列表
     * <p>
     * 按存储类型（如阿里云 OSS、OneDrive、本地存储等）筛选存储源，
     * 用于批量操作同类型存储源或统计分析。
     * </p>
     *
     * @param   type
     *          存储源类型枚举
     *
     * @return  指定类型的存储源列表
     */
    List<StorageSource> findByType(@Param("type") StorageTypeEnum type);


    /**
     * 根据存储源 ID 更新排序值
     * <p>
     * 用于拖拽排序功能，管理员调整存储源的显示顺序后更新排序值。
     * </p>
     *
     * @param   orderNum
     *          新的排序值，值越小越靠前
     *
     * @param   id
     *          存储源 ID
     */
    void updateSetOrderNumById(@Param("orderNum") int orderNum, @Param("id") Integer id);


    /**
     * 根据存储源的业务键（key）查询完整的存储源信息
     * <p>
     * key 是存储源在 URL 中使用的唯一标识符，通过此方法根据 URL 中的 key 获取对应的存储源实体。
     * </p>
     *
     * @param   storageKey
     *          存储源的业务键
     *
     * @return  存储源实体信息，如果不存在则返回 null
     */
	StorageSource findByStorageKey(@Param("storageKey") String storageKey);


    /**
     * 根据存储源的业务键（key）查询存储源 ID
     * <p>
     * 与 findByStorageKey 相比，此方法仅返回 ID，性能更优。
     * </p>
     *
     * @param   storageKey
     *          存储源的业务键
     *
     * @return  存储源 ID，如果不存在则返回 null
     */
    Integer findIdByStorageKey(@Param("storageKey") String storageKey);


    /**
     * 根据存储源 ID 查询存储源的业务键（key）
     * <p>
     * 反向查询，根据数据库主键 ID 获取 URL 中使用的唯一标识符。
     * </p>
     *
     * @param   id
     *          存储源 ID
     *
     * @return  存储源的业务键，如果不存在则返回 null
     */
    String findKeyById(@Param("id")Integer id);

}