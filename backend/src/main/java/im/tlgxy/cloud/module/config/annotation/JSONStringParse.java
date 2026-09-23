package im.tlgxy.cloud.module.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义注解，用于标记字段是否需要按照 JSON 字符串格式进行解析。
 * <p>
 * 当字段被此注解标记时，表示该字段的值是一个 JSON 格式的字符串，
 * 在序列化或反序列化过程中需要进行特殊的 JSON 解析处理。
 * <p>
 * 该注解作用于字段级别，在运行时保留，通过反射机制读取。
 *
 * @author zhaojun
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JSONStringParse {

}