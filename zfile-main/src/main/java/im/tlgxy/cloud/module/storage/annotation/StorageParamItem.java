package im.tlgxy.cloud.module.storage.annotation;

import im.tlgxy.cloud.module.storage.enums.StorageParamItemAnnoEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageParamTypeEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 存储源参数项注解，用于标记存储类型（如 S3、OneDrive 等）的参数配置字段。
 *
 * 每个存储源类型都有其特定的配置参数（如 AccessKey、SecretKey、Bucket 名称等），
 * 这些参数通过实现 {@link im.tlgxy.cloud.module.storage.model.param.IStorageParam} 接口的类来定义。
 * 此注解标注在这些参数字段上，用于描述字段在前端表单中的展示方式、校验规则、默认值等元数据信息。
 *
 * 框架会根据此注解自动生成存储源配置的前端表单，不同类型的字段会渲染为不同的表单控件（输入框、下拉框、开关等）。
 *
 * @author zhaojun
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StorageParamItem {

	/**
	 * 是否为捐赠版（Pro 版本）专属功能。
	 * 如果设置为 true，则该参数对应的功能仅在捐赠版中可用，非捐赠版用户看不到此配置项。
	 */
	boolean pro() default false;

	/**
	 * 字段在配置表单中的显示排序值。
	 * 值越小，在页面上显示越靠前。默认值为 99。
	 */
	int order() default 99;

	/**
	 * 参数的唯一键名。
	 * 如果为空字符串，则默认使用 Java 字段名称作为参数键。
	 * 该键会作为配置项在数据库和前端之间的标识符。
	 */
	String key() default "";

	/**
	 * 参数在前端页面上显示的名称（标签）。
	 * 用于告知用户该配置项的含义，如 "Access Key"、"Bucket 名称" 等。
	 */
	String name() default "";

	/**
	 * 字段在前端的展示类型。
	 * 默认为 {@link StorageParamTypeEnum#INPUT}（普通文本输入框）。
	 * 可选值包括：INPUT（文本输入）、TEXTAREA（多行文本）、SELECT（下拉选择）、SWITCH（开关）等。
	 */
	StorageParamTypeEnum type() default StorageParamTypeEnum.INPUT;

	/**
	 * 当 {@link #type()} 为 SELECT（下拉选择）类型时，定义可选的静态下拉选项值。
	 * 适用于下拉选项固定不变的场景，如选择存储类型、地域等。
	 */
	StorageParamSelectOption[] options() default {};

	/**
	 * 当 {@link #type()} 为 SELECT（下拉选择）类型时，通过实现 {@link StorageParamSelect} 接口的类动态获取选项值。
	 * 这种方式适用于下拉选项需要从外部动态获取的场景（如编码格式列表、存储桶列表等）。
	 * 具体选项值由实现类的 {@link StorageParamSelect#getOptions(StorageParamItem, im.tlgxy.cloud.module.storage.model.param.IStorageParam)} 方法提供。
	 */
	Class<? extends StorageParamSelect> optionsClass() default StorageParamSelect.class;

	/**
	 * 当 {@link #type()} 为 SELECT（下拉选择）类型时，是否允许用户在前端手动创建新的选项值。
	 * 默认为 false，即只允许从已有选项中选择。
	 */
	boolean optionAllowCreate() default false;

	/**
	 * 该参数值是否必填。
	 * 如果设置为 true（默认），则用户在保存配置时该字段不能为空，否则会校验失败并抛出异常。
	 */
	boolean required() default true;

	/**
	 * 参数的默认值。
	 * 如果用户未填写该字段，则使用此默认值。
	 * 支持 ${xxx} 格式的占位符变量，会尝试从配置文件（如 application.yml）中读取对应的值；
	 * 如果读取失败，则默认为空字符串。
	 */
	String defaultValue() default "";

	/**
	 * 参数的描述/提示信息。
	 * 在用户填写表单时，以提示文字的形式显示在字段下方，帮助用户理解该配置项的作用。
	 */
	String description() default "";

	/**
	 * 参数下方的帮助链接地址。
	 * 如果为空，则不显示链接。
	 * 通常用于链接到外部文档或帮助页面。
	 */
	String link() default "";

	/**
	 * 参数下方的帮助链接显示文本。
	 * 如果为空，则默认显示链接地址本身。
	 */
	String linkName() default "";

	/**
	 * 是否忽略该参数，既不传递给前端展示，也不保存到数据库。
	 * 通常用于标记临时的、内部使用的参数，这些参数不需要用户感知。
	 */
	boolean ignoreInput() default false;

	/**
	 * 是否在前端隐藏该字段。
	 * 与 ignoreInput 不同，hidden 只是前端不可见，但参数值仍然会传递给后端和保存到数据库。
	 */
	boolean hidden() default false;

	/**
	 * 字段可见性条件表达式。
	 * 当表达式结果为 true 时，该字段才会在前端显示。
	 * 支持 SpEL（Spring Expression Language）表达式，可以根据其他字段的值动态控制字段的显隐。
	 */
	String condition() default "";

	/**
	 * 仅覆盖指定的注解属性，用于简化子类继承父类时的注解配置。
	 *
	 * 在存储源参数类的继承体系中，子类的字段可能只需要修改父类注解的某几个属性（如 name、description 等），
	 * 而其他属性保持不变。如果直接完整复制父类注解的所有属性，后期维护困难且容易不同步。
	 *
	 * 使用此属性可以指定哪些字段以子类的注解值为准，其余属性则从父类继承，避免重复配置。
	 */
	StorageParamItemAnnoEnum[] onlyOverwrite() default {};

}
