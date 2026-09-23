package im.tlgxy.cloud.module.readme.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.readme.model.entity.ReadmeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存储源文档配置表 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供 README 文档配置表的基础 CRUD 操作。
 * 额外定义了按存储源 ID 查询和删除的自定义方法。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface ReadmeConfigMapper extends BaseMapper<ReadmeConfig> {


	/**
	 * 根据存储源 ID 查询文档配置
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @return  存储源文档配置列表
	 */
	List<ReadmeConfig> findByStorageId(@Param("storageId") Integer storageId);


	/**
	 * 根据存储源 ID 删除所有文档配置
	 *
	 * @param   storageId
	 *          存储源ID
	 *
	 * @return  删除记录数
	 */
	int deleteByStorageId(@Param("storageId") Integer storageId);

}