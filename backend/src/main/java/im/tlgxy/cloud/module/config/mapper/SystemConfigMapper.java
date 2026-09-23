package im.tlgxy.cloud.module.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.config.model.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;


/**
 * 系统配置 Mapper 接口，用于操作 system_config 表。
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基础的 CRUD 方法。
 * 同时定义了自定义查询和批量保存方法，通过对应的 XML 映射文件实现。
 *
 * @author zhaojun
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {

    /**
     * 查询所有系统配置记录。
     *
     * @return 系统配置实体列表
     */
    List<SystemConfig> findAll();


    /**
     * 根据配置项名称查询单条系统配置。
     *
     * @param name 系统配置项名称
     * @return 对应的系统配置实体，未找到返回 null
     */
    SystemConfig findByName(@Param("name")String name);


    /**
     * 批量保存系统配置记录。
     * <p>
     * 通常用于系统初始化时一次性插入所有默认配置项。
     *
     * @param list 待保存的系统配置实体列表
     * @return 成功保存的记录数
     */
    int saveAll(@Param("list")List<SystemConfig> list);

}