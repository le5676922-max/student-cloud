package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Referer（请求来源）校验注解。
 *
 * 标注了此注解的请求会被 AOP 切面拦截，校验 HTTP 请求头中的 Referer 字段是否符合配置的白名单规则。
 * 用于防止文件直链被其他网站盗用（盗链），确保只有来自允许域名的请求才能访问资源。
 *
 * 使用场景：通常标注在文件下载、图片预览等直链接口上，防止资源被第三方网站直接引用。
 *
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RefererCheck {

}
