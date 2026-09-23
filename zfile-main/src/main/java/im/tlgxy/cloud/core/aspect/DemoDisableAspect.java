package im.tlgxy.cloud.core.aspect;

import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.config.TlgxyCloudProperties;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import jakarta.annotation.Resource;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * 演示系统功能禁用切面。
 * <p>
 * 通过拦截带有 {@link DemoDisable} 注解的方法，在系统被配置为演示站点时
 * （即配置项 {@code tongligong-student-cloud.isDemoSite = true}），禁止该方法执行并抛出业务异常。
 * </p>
 * <p>
 * 适用场景：当 TlgxyCloud 部署为公开的在线演示站点时，需要禁用删除、修改配置等敏感操作，
 * 防止演示数据被恶意破坏。
 * </p>
 *
 * @author zhaojun
 */
// 声明此类为切面类
@Aspect
// 声明为 Spring 组件
@Component
public class DemoDisableAspect {

    /**
     * TlgxyCloud 配置属性，用于判断当前是否为演示站点
     */
    @Resource
    private TlgxyCloudProperties tlgxyCloudProperties;

    /**
     * 定义一个切点，匹配所有标记了 {@link DemoDisable} 注解的方法
     */
    @Pointcut("@annotation(im.tlgxy.cloud.core.annotation.DemoDisable)")
    public void demoDisable() {
    }

    /**
     * 在标记了 {@link DemoDisable} 注解的方法执行前进行校验。
     * 如果当前系统是演示站点，则阻止方法执行并抛出异常。
     *
     * @param joinPoint 切点，包含被拦截方法的信息
     * @throws BizException 当系统为演示站点时抛出，错误码为 DEMO_SITE_DISABLE_OPERATOR
     */
    @Before("demoDisable()")
    public void before(JoinPoint joinPoint) {
        // 检查系统是否为演示站点，如果是则禁止操作
        if (tlgxyCloudProperties.isDemoSite()) {
            throw new BizException(ErrorCode.DEMO_SITE_DISABLE_OPERATOR);
        }
    }

}
