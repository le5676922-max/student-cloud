package im.tlgxy.cloud.module.log.controller;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import com.github.xiaoymin.knife4j.annotations.ApiSort;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.link.model.request.QueryLoginLogRequest;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import im.tlgxy.cloud.module.log.service.LoginLogService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Date;
import java.util.List;

/**
 * 用户登录日志管理控制器
 * <p>
 * 提供登录日志的分页查询功能，支持按用户名、IP、User-Agent、Referer、登录结果、时间范围等条件筛选。
 * 所有接口均以 /admin/login/log 为前缀，供后台管理端使用。
 * </p>
 *
 * @author zhaojun
 */
@Tag(name = "登录日志管理")
@ApiSort(7)
@Controller
@RequestMapping("/admin/login/log")
public class LoginLogController {

    /**
     * 登录日志服务，封装了登录日志的数据库操作
     */
    @Resource
    private LoginLogService loginLogService;

    @Resource
    private TenantSecurityService tenantSecurityService;

    /** Explicit allowlist: even historical password fields never leave this endpoint. */
    public record LoginLogView(Integer id, String username, Date createTime, String ip,
                               String userAgent, String referer, String result) {
        static LoginLogView from(LoginLog log) {
            return new LoginLogView(log.getId(), log.getUsername(), log.getCreateTime(), log.getIp(),
                    log.getUserAgent(), log.getReferer(), log.getResult());
        }
    }

    /**
     * 分页查询登录日志列表
     * <p>
     * 支持按用户名、IP、User-Agent、Referer、登录结果、时间范围等条件进行筛选，
     * 并支持指定排序字段和排序方向。
     * </p>
     *
     * @param queryLoginLogRequest 查询登录日志的请求参数，包含分页、排序及各种筛选条件
     * @return 包含分页数据和登录日志列表的 AjaxJson 对象
     */
    @ApiOperationSupport(order = 1)
    @GetMapping("/list")
    @Operation(summary = "登录日志列表")
    @ResponseBody
    public AjaxJson<List<LoginLogView>> list(QueryLoginLogRequest queryLoginLogRequest) {
        // Defence in depth: service calls and invalid parameters never precede authorization.
        tenantSecurityService.requireSystemAdmin();
        queryLoginLogRequest.validateForQuery();
        // 创建分页对象，设置页码、每页条数和排序规则
        Page<LoginLog> pages = new Page<LoginLog>(queryLoginLogRequest.getPage(), queryLoginLogRequest.getLimit())
                .addOrder(queryLoginLogRequest.getOrderItem());

        // 构建动态查询条件，所有条件均为可选，只有参数不为空时才参与查询
        LambdaQueryWrapper<LoginLog> queryWrapper = new LambdaQueryWrapper<LoginLog>()
                .select(LoginLog::getId, LoginLog::getUsername, LoginLog::getCreateTime, LoginLog::getIp,
                        LoginLog::getUserAgent, LoginLog::getReferer, LoginLog::getResult)
                .like(StringUtils.isNotEmpty(queryLoginLogRequest.getUsername()), LoginLog::getUsername, queryLoginLogRequest.getUsername())
                .like(StringUtils.isNotEmpty(queryLoginLogRequest.getIp()), LoginLog::getIp, queryLoginLogRequest.getIp())
                .like(StringUtils.isNotEmpty(queryLoginLogRequest.getUserAgent()), LoginLog::getUserAgent, queryLoginLogRequest.getUserAgent())
                .like(StringUtils.isNotEmpty(queryLoginLogRequest.getReferer()), LoginLog::getReferer, queryLoginLogRequest.getReferer())
                .like(StringUtils.isNotEmpty(queryLoginLogRequest.getResult()), LoginLog::getResult, queryLoginLogRequest.getResult())
                .ge(ObjUtil.isNotEmpty(queryLoginLogRequest.getDateFrom()), LoginLog::getCreateTime, queryLoginLogRequest.getDateFrom())
                .le(ObjUtil.isNotEmpty(queryLoginLogRequest.getDateTo()), LoginLog::getCreateTime, queryLoginLogRequest.getDateTo());

        // 执行分页查询
        Page<LoginLog> selectResult = loginLogService.selectPage(pages, queryWrapper);
        // 返回分页数据，包括总记录数和当前页记录列表
        return AjaxJson.getPageData(selectResult.getTotal(), selectResult.getRecords().stream().map(LoginLogView::from).toList());
    }

}
