package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.*;

/**
 * 文件夹密码校验注解，用于判断是否有权限访问受密码保护的文件夹。
 *
 * 该注解支持在同一方法上重复使用（通过 {@link Repeatable} 元注解配合 {@link CheckPasswords} 容器注解实现），
 * 从而可以为不同的存储源或路径配置不同的密码校验规则。
 *
 * 使用场景：当用户访问某个存储源的某个文件夹时，如果该文件夹设置了访问密码，
 * 则通过此注解标记的方法会被 AOP 切面拦截，提取请求中的存储源 key、路径和密码参数进行校验。
 *
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CheckPasswords.class)
public @interface CheckPassword {

	/**
	 * 存储源 key 的 SpEL 字段表达式。
	 * 用于从方法参数中提取存储源的唯一标识 key，从而定位到具体的存储源配置。
	 */
	String storageKeyFieldExpression();

	/**
	 * 路径的 SpEL 字段表达式。
	 * 用于从方法参数中提取要访问的文件夹路径，以便查找该路径对应的密码配置。
	 */
	String pathFieldExpression();

	/**
	 * 密码的 SpEL 字段表达式。
	 * 用于从方法参数中提取用户输入的访问密码，与配置中的密码进行比对校验。
	 */
	String passwordFieldExpression();

	/**
	 * 路径是否直接指向文件夹。
	 * 默认为 true，表示 pathFieldExpression 提取的路径就是文件夹路径；
	 * 如果设置为 false，则表示提取的路径可能是文件路径，此时框架会自动取其父级目录作为目标文件夹路径进行校验。
	 */
	boolean pathIsDirectory() default true;

}
