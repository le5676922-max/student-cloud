package im.tlgxy.cloud.core.util;

import cn.hutool.core.util.StrUtil;

/**
 * URL 相关工具类，提供 URL 常用的解析和处理功能。
 *
 * <p>主要功能包括：</p>
 * <ul>
 *     <li>判断 URL 是否包含协议（http / https）</li>
 *     <li>为 URL 拼接查询参数</li>
 *     <li>获取 URL 中的协议部分</li>
 *     <li>移除 URL 中的协议部分</li>
 *     <li>提取 URL 中的域名部分</li>
 * </ul>
 *
 * @author zhaojun
 */
public class UrlUtils {

	/**
	 * 判断 URL 是否包含 HTTP 协议部分（http:// 或 https://）。
	 *
	 * <p>只检查字符串是否以 <code>http://</code> 或 <code>https://</code> 开头，
	 * 不涉及 URL 格式的验证。</p>
	 *
	 * @param 	url
	 * 			URL 地址字符串
	 *
	 * @return	如果以 http:// 或 https:// 开头返回 true，否则返回 false。
	 */
	public static boolean hasScheme(String url) {
		return url.startsWith("http://") || url.startsWith("https://");
	}

	/**
	 * 为 URL 拼接查询参数（query parameter）。
	 *
	 * <p>如果 URL 中已经包含 '?'（即已有查询参数），则使用 '&amp;' 拼接新参数；
	 * 否则使用 '?' 开始查询参数部分。新参数以 <code>name=value</code> 的形式追加。</p>
	 *
	 * @param 	url
	 * 			原始 URL 地址
	 *
	 * @param 	name
	 * 			参数名称
	 *
	 * @param 	value
	 * 			参数值
	 *
	 * @return	拼接查询参数后的 URL。
	 */
	public static String concatQueryParam(String url, String name, String value) {
		// 判断是否已有查询参数，决定使用 ? 还是 & 进行拼接
		if (StringUtils.contains(url, "?")) {
			return url + "&" + name + "=" + value;
		} else {
			return url + "?" + name + "=" + value;
		}
	}

	/**
	 * 获取 URL 中的协议部分（schema）。
	 *
	 * <p>通过判断 URL 是否以 http:// 或 https:// 开头（忽略大小写）来确定协议类型。
	 * 如果 URL 不以这两种协议开头，则默认返回 "http"。</p>
	 *
	 * @param 	url
	 * 			URL 地址字符串
	 *
	 * @return	协议名称："http" 或 "https"。如果不匹配任何已知协议则默认返回 "http"。
	 */
	public static String getSchema(String url) {
		if (StringUtils.startWithIgnoreCase(url, "http://")) {
			return "http";
		} else if (StringUtils.startWithIgnoreCase(url, "https://")) {
			return "https";
		} else {
			// 默认返回 http 协议
			return "http";
		}
	}

	/**
	 * 移除 URL 中的协议部分（http:// 或 https://）。
	 *
	 * <p>比较时不区分大小写。例如：</p>
	 * <ul>
	 *     <li><code>http://example.com/path</code> 移除后得到 <code>example.com/path</code></li>
	 *     <li><code>HTTPS://example.com/path</code> 移除后得到 <code>example.com/path</code></li>
	 * </ul>
	 *
	 * @param 	url
	 * 			URL 地址字符串
	 *
	 * @return	移除协议部分后的 URL。如果 URL 不包含协议部分则返回原 URL。
	 */
	public static String removeScheme(String url) {
		// 忽略大小写地检查并移除协议前缀
		if (StringUtils.startWithIgnoreCase(url, "http://")) {
			url = url.substring(7);
		} else if (StringUtils.startWithIgnoreCase(url, "https://")) {
			url = url.substring(8);
		}

		return url;
	}

	/**
	 * 获取 URL 中的域名部分（不包含端口号和路径）。
	 *
	 * <p>解析过程：</p>
	 * <ol>
	 *     <li>移除协议前缀（http:// 或 https://）</li>
	 *     <li>截取第一个 '/' 之前的部分（即主机部分）</li>
	 *     <li>按 ':' 分割，取第一部分作为域名（排除端口号）</li>
	 * </ol>
	 *
	 * <p>示例：</p>
	 * <ul>
	 *     <li><code>http://example.com:8080/path</code> 返回 <code>example.com</code></li>
	 *     <li><code>https://sub.example.com/path</code> 返回 <code>sub.example.com</code></li>
	 * </ul>
	 *
	 * @param 	url
	 * 			URL 地址字符串
	 *
	 * @return	域名部分；如果 URL 为空则返回 null。
	 */
	public static String getDomain(String url) {
		if (!StringUtils.isEmpty(url)) {
			// 移除协议前缀
			String newStr = url.replace("http://", "");
			newStr = newStr.replace("https://", "");

			// 截取第一个 '/' 之前的部分（即去除路径部分）
			int index = StrUtil.indexOf(newStr, '/');
			if (index > 0) {
				newStr = newStr.substring(0, index);
			}

			// 按 ':' 分割，取第一部分作为域名（去除端口号）
			String[] split = newStr.split(":");
			if (split.length > 1) {
				return split[0];
			} else {
				return newStr;
			}
		} else {
			return null;
		}
	}

}