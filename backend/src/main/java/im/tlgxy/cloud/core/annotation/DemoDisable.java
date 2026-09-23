package im.tlgxy.cloud.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 演示系统禁用功能注解。
 * <p>
 * 将此注解标记在方法上，配合 {@link im.tlgxy.cloud.core.aspect.DemoDisableAspect} 切面，
 * 当系统配置为演示站点（tongligong-student-cloud.isDemoSite = true）时，会拦截并禁止该方法执行，
 * 抛出业务异常以提示演示系统不可操作。
 * </p>
 * <p>
 * 使用场景：生产环境中某些敏感操作（如删除、修改配置等）在演示站点中需要被禁用，
 * 通过此注解可以统一控制。
 * </p>
 *
 * @author zhaojun
 */
// 该注解只能用于方法上
@Target(ElementType.METHOD)
// 该注解在运行时可通过反射获取
@Retention(RetentionPolicy.RUNTIME)
public @interface DemoDisable {

}
