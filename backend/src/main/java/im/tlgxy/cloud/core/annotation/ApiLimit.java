package im.tlgxy.cloud.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流注解。
 * <p>
 * 将此注解标记在方法上，配合 {@link im.tlgxy.cloud.core.aspect.ApiLimitAspect} 切面，
 * 可以限制该方法在指定时间窗口内被同一 IP 调用的最大次数，防止接口被频繁刷取。
 * </p>
 * <p>
 * 使用示例：<br>
 * <pre>{@code
 *   @ApiLimit(timeout = 60, maxCount = 10, timeUnit = TimeUnit.SECONDS)
 *   public AjaxJson<?> someApi() { ... }
 * }</pre>
 * 表示 60 秒内同一 IP 最多只能调用该方法 10 次。
 * </p>
 *
 * @author zhaojun
 */
// 该注解只能用于方法上
@Target(ElementType.METHOD)
// 该注解在运行时可通过反射获取
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiLimit {

    /**
     * 限流时间窗口的持续时间（与 {@link #timeUnit()} 配合使用）
     *
     * @return 持续时间数值
     */
    int timeout();

    /**
     * 限流时间窗口的时间单位，默认为秒
     *
     * @return 时间单位枚举值
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 单位时间窗口内允许访问的最大次数
     *
     * @return 最大访问次数
     */
    long maxCount();

}
