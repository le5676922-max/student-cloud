package im.tlgxy.cloud.core.util;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Glob 规则表达式匹配工具类
 * <p>
 * 基于 Java NIO 的 {@link PathMatcher} 和 glob 表达式语法，提供路径模式匹配功能。
 * 主要用于文件访问规则（如文件夹密码、文件忽略规则等）的匹配判断。
 * <p>
 * 核心功能：
 * <ul>
 *     <li>兼容性 Glob 匹配：解决了 Java 默认 glob 无法匹配以 {@code /} 结尾路径的问题</li>
 *     <li>缓存优化：将 {@link PathMatcher} 实例缓存起来，避免重复创建</li>
 * </ul>
 * <p>
 * 兼容性问题说明：<br>
 * 默认的 glob 表达式无法匹配以下情况：
 * <ul>
 *     <li>pattern: {@code /a/**}</li>
 *     <li>test1: {@code /a}</li>
 *     <li>test2: {@code /a/}</li>
 * </ul>
 * test1 和 test2 均无法匹配 pattern。本工具类通过在 test 内容后拼接 "/xx" 的方式
 * 解决了这一兼容性问题。
 *
 * @author zhaojun
 */
public class PatternMatcherUtils {

	/**
	 * PathMatcher 缓存
	 * <p>
	 * 以 glob 表达式字符串为键，缓存对应的 {@link PathMatcher} 实例。
	 * 由于 glob 表达式通常不会频繁变化，缓存可以显著减少重复创建 PathMatcher 的开销。
	 */
	private static final Map<String, PathMatcher> PATH_MATCHER_MAP = new HashMap<>();

	/**
	 * 兼容模式的 Glob 表达式匹配
	 * <p>
	 * 解决 Java 默认 glob 表达式不支持以下情况的兼容性问题：
	 * <ul>
	 *     <li>pattern: {@code /a/**}</li>
	 *     <li>test1: {@code /a}</li>
	 *     <li>test2: {@code /a/}</li>
	 * </ul>
	 * <p>
	 * test1 和 test2 均无法匹配上述 pattern。此方法兼容了这种情况，即对 test 内容后拼接 "/xx"
	 *（任意字符均可），使其可以匹配上带有 {@code /**} 或 {@code /*} 结尾的 pattern。
	 * <p>
	 * <b>注意：</b>此方法仅对路径名生效，不支持包含文件名的情况，
	 * 即 test 参数应为目录路径，不应包含文件名。
	 *
	 * @param pattern Glob 规则表达式，如 "{@code /data/**}"
	 * @param test    待匹配的路径字符串，如 "{@code /data}"
	 * @return 是否匹配成功：true 表示匹配，false 表示不匹配
	 */
	public static boolean testCompatibilityGlobPattern(String pattern, String test) {
		// 如果规则表达式最开始没有 "/"，则兼容性地在最前方加上 "/"
		// 确保与默认的文件系统路径格式保持一致
		if (!StringUtils.startWith(pattern, StringUtils.SLASH)) {
			pattern = StringUtils.SLASH + pattern;
		}

		// 兼容性处理：在 test 末尾拼接触发匹配的辅助路径
		// 当 pattern 以 /** 或 /* 结尾时，需要额外拼接字符才能正确匹配纯目录路径
		test = StringUtils.concat(test, StringUtils.SLASH);
		if (StringUtils.endWith(pattern, "/**") || StringUtils.endWith(pattern, "/*")) {
			test += "xxx";
		}
		return testGlobPattern(pattern, test);
	}


	/**
	 * 测试 Glob 规则表达式和文件路径是否匹配
	 * <p>
	 * 内部方法，使用 NIO {@link PathMatcher} 进行实际的 glob 匹配。
	 * 匹配策略：如果 PathMatcher 匹配成功，或者 pattern 与 test 字面量完全相同，
	 * 则视为匹配成功。
	 *
	 * @param pattern Glob 规则表达式，如 "{@code glob:/data/**}"
	 * @param test    待测试的路径字符串
	 * @return 是否匹配成功
	 */
	private static boolean testGlobPattern(String pattern, String test) {
		// 从缓存中取出 PathMatcher，如果不存在则创建新的并缓存
		// 这样可以避免重复初始化 PatternMatcher 带来的性能开销
		PathMatcher pathMatcher = PATH_MATCHER_MAP.getOrDefault(pattern, FileSystems.getDefault().getPathMatcher("glob:" + pattern));
		PATH_MATCHER_MAP.put(pattern, pathMatcher);

		// 使用 PathMatcher 进行 glob 匹配，或者直接进行字符串相等判断
		return pathMatcher.matches(Paths.get(test)) || StringUtils.equals(pattern, test);
	}

}