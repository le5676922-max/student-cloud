package im.tlgxy.cloud.module.filter.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件过滤隐藏模式枚举。
 * <p>
 * 定义了过滤规则匹配到文件/文件夹后的三种处理方式：
 * <ul>
 *   <li>{@link #HIDDEN} —— 仅在前台文件列表中隐藏，但通过直接链接仍可访问</li>
 *   <li>{@link #INACCESSIBLE} —— 隐藏且禁止访问（适用于目录），直接访问会返回 403</li>
 *   <li>{@link #DISABLE_DOWNLOAD} —— 隐藏且禁止下载（适用于文件），下载请求会返回 403</li>
 * </ul>
 *
 * @author zhaojun
 */
@Getter
@AllArgsConstructor
public enum FilterConfigHiddenModeEnum {

	/**
	 * 仅隐藏 —— 在文件列表中不可见，但仍可通过直链访问
	 */
	HIDDEN("hidden"),

	/**
	 * 隐藏并不可访问 —— 针对目录，访问该目录会返回禁止访问
	 */
	INACCESSIBLE("inaccessible"),

	/**
	 * 隐藏并不可下载 —— 针对文件，下载该文件会返回禁止下载
	 */
	DISABLE_DOWNLOAD("disable_download");

	/**
	 * 枚举值，用于 MyBatis-Plus 的 @EnumValue 和 Jackson 的 @JsonValue 序列化
	 */
	@EnumValue
	@JsonValue
	private final String value;

}
