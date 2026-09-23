package im.tlgxy.cloud.module.filter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.filter.model.entity.FilterConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 过滤配置 Mapper 接口，用于操作 filter_config 表。
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基础 CRUD 方法。
 * 同时定义了按存储源 ID 查询、删除，以及按隐藏模式查询的自定义方法。
 *
 * @author zhaojun
 */
@Mapper
public interface FilterConfigMapper extends BaseMapper<FilterConfig> {

    /**
     * 根据存储源 ID 查询该存储源下的所有过滤配置。
     *
     * @param storageId 存储源 ID
     * @return 该存储源下的过滤器配置列表
     */
    List<FilterConfig> findByStorageId(@Param("storageId") Integer storageId);

    /**
     * 根据存储源 ID 删除该存储源下的所有过滤配置。
     *
     * @param storageId 存储源 ID
     * @return 删除的记录条数
     */
    int deleteByStorageId(@Param("storageId") Integer storageId);

    /**
     * 查询指定存储源中所有模式为"禁止访问"（inaccessible）的过滤规则。
     * <p>
     * 这些规则用于阻止用户访问匹配的目录或文件。
     *
     * @param storageId 存储源 ID
     * @return 禁止访问的过滤规则列表
     */
    List<FilterConfig> findByStorageIdAndInaccessible(@Param("storageId")Integer storageId);

    /**
     * 查询指定存储源中所有模式为"禁止下载"（disable_download）的过滤规则。
     * <p>
     * 这些规则用于阻止用户下载匹配的文件。
     *
     * @param storageId 存储源 ID
     * @return 禁止下载的过滤规则列表
     */
    List<FilterConfig> findByStorageIdAndDisableDownload(@Param("storageId")Integer storageId);

}
