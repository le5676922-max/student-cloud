package im.tlgxy.cloud.module.log.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import im.tlgxy.cloud.module.log.mapper.LoginLogMapper;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 登录日志 Service
 * <p>
 * 封装登录日志的保存和分页查询功能。
 * 在用户登录时记录登录信息（无论成功或失败），供管理员审计和排查。
 * </p>
 *
 * @author zhaojun
 */
@Service
public class LoginLogService {

    /** 登录日志 Mapper */
    @Resource
    private LoginLogMapper loginLogMapper;

    /**
     * 保存一条登录日志记录
     *
     * @param loginLog 要保存的登录日志实体
     */
    public void save(LoginLog loginLog) {
        loginLogMapper.insert(loginLog);
    }

    /**
     * 分页查询登录日志
     *
     * @param pages        分页参数对象
     * @param queryWrapper 查询条件包装器
     * @return 分页查询结果
     */
    public Page<LoginLog> selectPage(Page<LoginLog> pages, Wrapper<LoginLog> queryWrapper) {
        return loginLogMapper.selectPage(pages, queryWrapper);
    }
}
