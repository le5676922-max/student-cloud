package im.tlgxy.cloud.module.readme.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Readme 展示模式枚举
 * <p>
 * 定义 README 文档在文件列表中的显示位置：
 * <ul>
 *   <li>TOP - 在文件列表顶部显示</li>
 *   <li>BOTTOM - 在文件列表底部显示</li>
 *   <li>DIALOG - 以弹窗形式显示</li>
 * </ul>
 * value 字段同时用于 MyBatis-Plus 的数据库映射（@EnumValue）和 Jackson 的 JSON 序列化（@JsonValue）。
 * </p>
 *
 * @author zhaojun
 */
@Getter
@AllArgsConstructor
public enum ReadmeDisplayModeEnum {

	/**
	 * 在文件列表顶部显示 README
	 */
	TOP("top"),

	/**
	 * 在文件列表底部显示 README
	 */
	BOTTOM("bottom"),

	/**
	 * 以弹窗形式显示 README
	 */
	DIALOG("dialog");

	/**
	 * 展示模式的值（用于数据库存储和 JSON 序列化）
	 */
	@EnumValue
	@JsonValue
	private final String value;

}