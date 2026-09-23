package im.tlgxy.cloud.module.storage.model.bo;

import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.module.storage.annotation.StorageParamSelectOption;
import im.tlgxy.cloud.module.storage.model.enums.StorageParamTypeEnum;
import lombok.Data;
import lombok.Getter;

import java.util.List;

/**
 * 存储源参数定义业务对象（BO）
 * <p>
 * 定义了存储源配置时需要填写的每个参数项的元数据信息。
 * 该对象描述了前端表单中每个字段的展示方式和校验规则，
 * 包括字段名称、类型、是否必填、默认值、下拉选项等。
 * </p>
 * <p>
 * 通过对此对象的解析，前端可以动态渲染出不同存储源类型对应的配置表单。
 * 例如：S3 存储源需要 AccessKey、SecretKey 等字段，而本地存储只需要文件路径。
 * 每个具体的存储源实现类通过注解 {@code @StorageParamItem} 声明其所需的参数，
 * 系统将这些注解信息转换为 StorageSourceParamDef 列表供前端使用。
 * </p>
 *
 * @author zhaojun
 */
@Data
public class StorageSourceParamDef {

	/**
	 * 字段在前端表单中的显示排序值，值越小越靠前
	 */
	private int order;

	/**
	 * 参数的唯一标识键，用于后续获取用户填写的值
	 * 对应存储源配置表中 name 字段的值
	 */
	private String key;

	/**
	 * 参数的显示名称，前端表单中该字段的标签文字
	 */
	private String name;

	/**
	 * 参数的描述信息，用于前端显示帮助提示
	 */
	private String description;

	/**
	 * 该参数是否必填，true 表示用户必须填写
	 */
	private boolean required;

	/**
	 * 参数的默认值，当用户未填写时使用此值
	 */
	private String defaultValue;

	/**
	 * 与该参数相关的帮助文档链接地址
	 */
	private String link;

	/**
	 * 帮助链接的显示名称（链接文字）
	 */
	private String linkName;

	/**
	 * 是否为专业版（Pro）功能，true 表示只有专业版用户才可以使用
	 */
	private boolean pro;

	/**
	 * 字段的前端输入类型
	 * 支持 input（文本输入框）、select（下拉选择框）、switch（开关）等
	 */
	private StorageParamTypeEnum type;

	/**
	 * 当 type 为 select 时的下拉选项列表
	 */
	private List<Options> options;

	/**
	 * 当 type 为 select 时，是否允许用户在前端手动输入并创建新的选项值
	 */
	private boolean optionAllowCreate;

	/**
	 * 条件表达式，控制该字段的显示与隐藏
	 * 表达式结果为 true 时才在前端显示此字段，用于实现字段间的联动效果
	 * 例如：选择了"A"选项后才显示某些后续字段
	 */
	private String condition;

	/**
	 * 是否在前端完全隐藏该字段
	 * 用于保存系统内部使用的参数，不需要用户填写
	 */
	private boolean hidden;

	/**
	 * 下拉选项实体内部类
	 * <p>
	 * 定义下拉选择框中每个选项的标签（前端显示文字）和值（后端使用的实际值）。
	 * 支持三种构造方式：仅传值（标签和值相同）、分别传标签和值、从注解中获取。
	 * </p>
	 */
	@Getter
	public static class Options {

		/** 选项在前端显示的标签文字 */
		private final String label;

		/** 选项对应的实际值 */
		private final String value;

		/**
		 * 创建一个标签和值相同的选项
		 *
		 * @param value  选项的值，同时作为标签显示
		 */
		public Options(String value) {
			this.label = value;
			this.value = value;
		}

		/**
		 * 创建一个标签和值不同的选项
		 *
		 * @param label  前端显示的标签文字
		 * @param value  选项的实际值
		 */
		public Options(String label, String value) {
			this.label = label;
			this.value = value;
		}

		/**
		 * 从 {@link StorageParamSelectOption} 注解中创建选项
		 * <p>
		 * 注解中 label 字段可能为空，此时使用 value 作为标签。
		 * 通过 StringUtils.firstNonNull 方法处理标签为空的情况。
		 * </p>
		 *
		 * @param storageParamSelectOption  定义选项的注解实例
		 */
		public Options(StorageParamSelectOption storageParamSelectOption) {
			this.value = storageParamSelectOption.value();
			this.label = StringUtils.firstNonNull(storageParamSelectOption.label(), storageParamSelectOption.value());
		}

	}

}