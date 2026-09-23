package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 捐赠版（Pro 版本）功能校验注解。
 *
 * 标注了此注解的方法会被 AOP 切面拦截，检测当前运行的 TlgxyCloud 实例是否为合法的捐赠版。
 * 如果检测未通过（例如非捐赠版或授权无效），则会拒绝该请求并返回相应的错误信息。
 *
 * 使用场景：标注在只有捐赠版才能使用的功能接口上，如高级存储源的某些操作。
 *
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProCheck {
}
