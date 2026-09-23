package im.tlgxy.cloud.module.password.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.password.model.entity.PasswordConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 存储源密码配置表 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供密码配置表的基础 CRUD 操作。
 * 额外定义了按存储源 ID 查询和删除的自定义方法。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface PasswordConfigMapper extends BaseMapper<PasswordConfig> {

    /**
     * 根据存储源 ID 获取密码规则配置
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  存储源密码规则配置列表
     */
    List<PasswordConfig> findByStorageId(@Param("storageId") Integer storageId);


    /**
     * 根据存储源 ID 删除所有密码规则配置
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  删除记录数
     */
    int deleteByStorageId(@Param("storageId") Integer storageId);

}