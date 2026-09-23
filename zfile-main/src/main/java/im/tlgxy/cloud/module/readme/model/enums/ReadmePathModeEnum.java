package im.tlgxy.cloud.module.readme.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Readme 路径模式枚举
 * <p>
 * 定义 README 文档路径表达式的匹配方式：
 * <ul>
 *   <li>RELATIVE - 相对路径模式：表达式匹配的是相对于用户根目录的路径</li>
 *   <li>ABSOLUTE - 绝对路径模式：表达式匹配的是包含用户根目录的完整路径</li>
 * </ul>
 * value 字段同时用于 MyBatis-Plus 的数据库映射（@EnumValue）和 Jackson 的 JSON 序列化（@JsonValue）。
 * </p>
 *
 * @author zhaojun
 */
@Getter
@AllArgsConstructor
public enum ReadmePathModeEnum {

	/**
	 * 相对路径模式
	 */
    RELATIVE("relative"),

	/**
	 * 绝对路径模式
	 */
    ABSOLUTE("absolute");

	/**
	 * 路径模式的值（用于数据库存储和 JSON 序列化）
	 */
	@EnumValue
	@JsonValue
	private final String value;

}