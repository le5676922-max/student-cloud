package im.tlgxy.cloud.module.config.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件点击模式枚举，用于控制文件是单击打开还是双击打开。
 * <p>
 * 该设置影响前台文件列表中的文件/文件夹打开行为：
 * <ul>
 *   <li>{@link #CLICK} —— 单击即可打开文件或进入文件夹</li>
 *   <li>{@link #DBCLICK} —— 需要双击才能打开文件或进入文件夹</li>
 * </ul>
 * <p>
 * 支持 PC 端和移动端分别配置。
 *
 * @author zhaojun
 */
@Getter
@AllArgsConstructor
public enum FileClickModeEnum {

	/**
	 * 单击打开文件/文件夹
	 */
	CLICK("click"),

	/**
	 * 双击打开文件/文件夹
	 */
	DBCLICK("dbclick");

	/**
	 * 枚举值，用于 MyBatis-Plus 的 @EnumValue 和 Jackson 的 @JsonValue 序列化
	 */
	@EnumValue
	@JsonValue
	private final String value;

}
