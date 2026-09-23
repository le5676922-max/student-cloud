package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link CheckPassword} 注解的容器注解，用于支持在同一方法上多次使用 {@link CheckPassword} 注解。
 *
 * Java 的重复注解机制要求定义一个容器注解来存放多个同类型注解实例。
 * 当需要在同一个 Controller 方法上对多个不同的存储源或路径进行密码校验时，
 * 可以多次标注 {@link CheckPassword} 注解，编译器会将其自动包装为此容器注解。
 *
 * @see CheckPassword
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckPasswords {

    /**
     * 存放多个 {@link CheckPassword} 注解实例的数组。
     * 由 Java 编译器在编译时自动填充，开发者通常无需手动使用此属性。
     */
    CheckPassword[] value();

}
