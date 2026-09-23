package im.tlgxy.cloud.module.storage.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 存储源参数下拉选项注解，用于在 {@link StorageParamItem} 注解的 {@link StorageParamItem#options()}
 * 属性中静态定义下拉选择框的选项值。
 *
 * 该注解的作用目标为 ANNOTATION_TYPE（注解类型），意味着它被设计为嵌套在其他注解中使用的元注解。
 * 每个选项由 label（前端显示的文本）和 value（实际存储的值）组成。
 *
 * 使用示例：
 * <pre>
 * &#64;StorageParamItem(
 *     type = StorageParamTypeEnum.SELECT,
 *     options = {
 *         &#64;StorageParamSelectOption(label = "选项一", value = "1"),
 *         &#64;StorageParamSelectOption(label = "选项二", value = "2")
 *     }
 * )
 * private String someField;
 * </pre>
 *
 * @author zhaojun
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StorageParamSelectOption {

	/**
	 * 选项在前端下拉列表中显示的文本（标签）。
	 */
	String label() default "";

	/**
	 * 选项实际存储的值。
	 * 当用户选择该选项后，此值会被保存到数据库或传递给后端。
	 * 注意：此属性没有默认值，使用时必须显式指定。
	 */
	String value();

}
