package im.tlgxy.cloud.module.user.aspect;

import cn.hutool.extra.servlet.JakartaServletUtil;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.log.model.entity.LoginLog;
import im.tlgxy.cloud.module.log.service.LoginLogService;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import im.tlgxy.cloud.module.user.model.request.UserLoginRequest;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 登录日志切面类
 * <p>
 * 使用 AOP 环绕通知拦截用户登录方法，根据系统配置的登录日志记录模式，
 * 自动记录用户的登录信息（用户名、IP、User-Agent、登录结果等）到数据库中。
 * 支持关闭或记录登录事件；所有历史模式均不记录密码。
 * </p>
 *
 * @author zhaojun
 */
@Aspect
@Component
@Slf4j
public class LoginLogAspect {

    /**
     * 系统配置服务，用于获取登录日志记录模式等配置信息
     */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 登录日志服务，用于保存登录日志记录
     */
    @Resource
    private LoginLogService loginLogService;

    /**
     * HTTP 请求对象，用于获取客户端 IP、User-Agent 等信息
     */
    @Resource
    private HttpServletRequest httpServletRequest;

    /**
     * 默认的登录成功提示信息
     */
    public static final String DEFAULT_LOGIN_SUCCESS_RESULT = "登录成功";

    /**
     * 登录日志环绕通知
     * <p>
     * 拦截 UserController.doLogin() 方法，在方法执行前后记录登录日志。
     * 根据系统配置的登录日志模式决定是否记录以及如何处理密码字段。
     * </p>
     *
     * @param pjp 切点连接点，用于执行目标方法
     * @return 目标方法的返回值
     * @throws Throwable 目标方法可能抛出的异常
     */
    @Around(value = "execution(* im.tlgxy.cloud.module.user.controller.UserController.doLogin(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        // 获取系统配置，判断登录日志记录模式
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        LoginLogModeEnum loginLogMode = systemConfig.getLoginLogMode();

        // 如果日志模式为关闭，直接执行目标方法，不记录日志
        if (loginLogMode == LoginLogModeEnum.OFF) {
            return pjp.proceed();
        }

        // 获取方法的第一个参数 UserLoginRequest 对象
        Object[] args = pjp.getArgs();
        Object arg = args[0];
        UserLoginRequest userLoginRequest = (UserLoginRequest) arg;

        // 构建登录日志实体，填充基本信息
        LoginLog loginLog = new LoginLog();
        loginLog.setUsername(userLoginRequest.getUsername());
        // Legacy modes remain compatible, but passwords never enter the log entity.
        loginLog.setPassword("******");
        // login_log uses second-precision DATETIME. Truncate explicitly so MySQL cannot
        // round a just-recorded attempt into the future and omit it from current statistics.
        loginLog.setCreateTime(new Date(System.currentTimeMillis() / 1000 * 1000));
        loginLog.setIp(JakartaServletUtil.getClientIP(httpServletRequest));
        loginLog.setUserAgent(httpServletRequest.getHeader(HttpHeaders.USER_AGENT));
        loginLog.setReferer(httpServletRequest.getHeader(HttpHeaders.REFERER));

        // 默认登录结果为成功
        String msg = DEFAULT_LOGIN_SUCCESS_RESULT;
        try {
            // 执行目标登录方法
            return pjp.proceed();
        } catch (Throwable throwable) {
            // 登录失败时记录异常信息
            msg = throwable.getMessage();
            throw throwable;
        } finally {
            // 设置登录结果并保存日志
            loginLog.setResult(msg);
            loginLogService.save(loginLog);
        }
    }

}
