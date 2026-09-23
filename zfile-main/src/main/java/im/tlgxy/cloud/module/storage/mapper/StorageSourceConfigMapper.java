package im.tlgxy.cloud.module.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;


/**
 * 存储源拓展设置 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 {@link BaseMapper}，拥有基础的 CRUD 方法。
 * 同时定义了与存储源配置相关的自定义 SQL 操作，包括按存储源 ID 查询、删除和批量插入配置项。
 * 这些配置在 XML 映射文件中实现，通常涉及 storage_source_config 表。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface StorageSourceConfigMapper extends BaseMapper<StorageSourceConfig> {

    /**
     * 根据存储源 ID 查询存储源拓展配置，并按照配置 ID 排序
     * <p>
     * 用于获取某个存储源的所有额外配置项（如 OAuth2 的 clientId、clientSecret 等），
     * 结果按 ID 升序排列以保证顺序一致性。
     * </p>
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  存储源拓展配置列表，按 ID 升序排列
     */
    List<StorageSourceConfig> findByStorageIdOrderById(@Param("storageId") Integer storageId);


    /**
     * 根据存储源 ID 删除存储源拓展配置
     * <p>
     * 在更新存储源配置时，先删除旧配置再批量插入新配置，实现全量替换更新。
     * </p>
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  删除的记录数
     */
    int deleteByStorageId(@Param("storageId") Integer storageId);


    /**
     * 批量插入存储源拓展配置
     * <p>
     * 支持一次性插入多条配置记录，通常与 deleteByStorageId 配合使用实现配置的全量更新。
     * </p>
     *
     * @param   list
     *          存储源拓展配置列表
     *
     * @return  插入的记录数
     */
    int insertList(@Param("list") List<StorageSourceConfig> list);

}