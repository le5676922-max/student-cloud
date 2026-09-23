package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 直链（下载/访问）频率限制注解。
 *
 * 标注了此注解的 Controller 方法会被 AOP 切面拦截，校验当前请求的访问频率是否超过配置的阈值。
 * 主要用于防止恶意高频下载或爬虫行为，保护服务器资源。
 *
 * 使用场景：通常标注在文件下载、预览等生成直链并允许外部访问的接口上。
 *
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LinkRateLimiter {

}
