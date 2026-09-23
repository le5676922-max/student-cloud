package im.tlgxy.cloud.module.storage.annotation;

import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 存储源文件操作权限校验注解。
 *
 * 标注了此注解的 Controller 方法会被 AOP 切面拦截，校验当前请求是否具有执行指定文件操作类型的权限。
 * 权限校验包括：文件是否允许该操作（如上传、下载、删除、重命名等）、存储源是否启用了该操作类型等。
 *
 * 使用场景：通常标注在文件操作相关的接口上，如文件上传、删除、重命名、新建文件夹等。
 *
 * @author zhaojun
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StoragePermissionCheck {

	/**
	 * 需要校验的文件操作类型。
	 * 取值为 {@link FileOperatorTypeEnum} 枚举中定义的操作类型，
	 * 例如：UPLOAD（上传）、DOWNLOAD（下载）、DELETE（删除）、RENAME（重命名）等。
	 * 切面会根据此值检查对应操作是否被允许。
	 */
	FileOperatorTypeEnum action();

}
