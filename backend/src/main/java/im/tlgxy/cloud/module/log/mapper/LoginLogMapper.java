package im.tlgxy.cloud.module.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供登录日志表的基础 CRUD 操作。
 * 当前不需要额外的自定义查询方法，所有操作均通过 BaseMapper 提供的通用方法完成。
 * </p>
 *
 * @author zhaojun
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}