package im.tlgxy.cloud.core.util;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LRUCache;
import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 字符串相关工具类，继承自 {@link CharSequenceUtil} 并实现 {@link StrPool} 接口。
 *
 * <p>提供一系列常用的字符串处理功能，主要包括：</p>
 * <ul>
 *     <li>URL 路径中斜杠（/）的去除、去重与拼接</li>
 *     <li>URL 编解码（支持忽略斜杠的部分编码）</li>
 *     <li>URL 协议（schema）的强制设置</li>
 *     <li>URL 中 Host 部分的替换</li>
 *     <li>字符串驼峰转下划线格式转换</li>
 *     <li>字符串前后缀判断与补充</li>
 * </ul>
 *
 * <p>对于重复斜杠的去除操作，使用了 LRU 缓存以提高重复调用时的性能。</p>
 *
 * @author zhaojun
 */
public class StringUtils extends CharSequenceUtil implements StrPool {

    /**
     * HTTP 协议名称常量
     */
    public static final String HTTP = "http";

    /**
     * 协议标记符，用于识别 URL 中协议与路径的分隔位置（例如 http:// 或 https://）
     */
    public static final String PROTOCOL_MARKER = "://";

    /**
     * LRU 缓存，用于缓存去除重复斜杠后的结果，避免对相同路径重复计算。
     * 最大容量为 1000，超出时自动淘汰最少使用的条目。
     */
    private static final LRUCache<String, String> CACHE = CacheUtil.newLRUCache(1000);

    /**
     * 移除 URL 路径前后的所有 '/' 字符。
     *
     * <p>该方法会先移除路径开头的所有斜杠，再移除结尾的所有斜杠，
     * 但不会影响中间的斜杠（如协议标记符 <code>://</code> 中的斜杠）。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  移除前后斜杠后的路径。
     *          如 path = '/folder1/file1/' 返回 'folder1/file1'；
     *          如 path = '///folder1/file1//' 返回 'folder1/file1'。
     */
    public static String trimSlashes(String path) {
        // 先去除开头的所有斜杠
        path = trimStartSlashes(path);
        // 再去除结尾的所有斜杠
        path = trimEndSlashes(path);
        return path;
    }


    /**
     * 移除 URL 路径开头的所有 '/' 字符。
     *
     * <p>循环检查路径是否以 '/' 开头，如果是则将其移除，直到不以 '/' 开头为止。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  移除开头斜杠后的路径。
     *          如 path = '/folder1/file1' 返回 'folder1/file1'；
     *          如 path = '//folder1/file1' 返回 'folder1/file1'。
     */
    public static String trimStartSlashes(String path) {
        // 空字符串直接返回，不做处理
        if (isEmpty(path)) {
            return path;
        }

        // 循环移除开头的每一个 '/' 字符
        while (path.startsWith(SLASH)) {
            path = path.substring(1);
        }

        return path;
    }


    /**
     * 移除 URL 路径结尾的所有 '/' 字符。
     *
     * <p>循环检查路径是否以 '/' 结尾，如果是则将其移除，直到不以 '/' 结尾为止。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  移除结尾斜杠后的路径。
     *          如 path = '/folder1/file1/' 返回 '/folder1/file1'；
     *          如 path = '/folder1/file1///' 返回 '/folder1/file1'。
     */
    public static String trimEndSlashes(String path) {
        // 空字符串直接返回，不做处理
        if (isEmpty(path)) {
            return path;
        }

        // 循环移除结尾的每一个 '/' 字符
        while (path.endsWith(SLASH)) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }


    /**
     * 去除路径中所有重复的 '/'，将连续的多个 '/' 压缩为单个 '/'。
     *
     * <p>该方法同时会处理以下情况：</p>
     * <ul>
     *     <li>如果路径以多个 '/' 开头且没有协议部分，开头多余的 '/' 会被去除；</li>
     *     <li>协议标记符（<code>://</code>）中的斜杠不受影响，即 http:// 和 https:// 保持原样；</li>
     *     <li>路径中间任意位置的连续多个 '/' 都会被压缩为一个 '/'。</li>
     * </ul>
     *
     * <p>结果会被缓存到 LRU 缓存中，以提高对同一路径重复调用时的性能。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  去除重复斜杠后的路径。
     *          如 path = '/folder1//file1/' 返回 '/folder1/file1/'；
     *          如 path = '/folder1////file1///' 返回 '/folder1/file1/'。
     */
    public static String removeDuplicateSlashes(String path) {
        if (isEmpty(path)) {
            return path;
        }

        // 使用缓存避免对相同路径重复计算
        return CACHE.get(path, false, () -> {
            StringBuilder sb = new StringBuilder(path.length());
            // 查找协议标记符（://）的位置
            int protocolIndex = path.indexOf(PROTOCOL_MARKER);

            int pathStartIndex = 0;

            // 1. 处理协议部分：如果存在协议标记，保留协议名称和 ://
            if (protocolIndex > -1) {
                // 找到协议名称的实际开始位置（跳过开头可能存在的多余 '/'）
                int schemeStartIndex = 0;
                while (schemeStartIndex < protocolIndex && path.charAt(schemeStartIndex) == '/') {
                    schemeStartIndex++;
                }

                sb.append(path, schemeStartIndex, protocolIndex);
                sb.append(PROTOCOL_MARKER);

                // 路径部分从协议标记之后开始
                pathStartIndex = protocolIndex + PROTOCOL_MARKER.length();
            }

            // 2. 处理路径部分：遍历字符，跳过连续重复的 '/'
            if (pathStartIndex < path.length()) {
                char lastChar;
                char firstPathChar = path.charAt(pathStartIndex);
                sb.append(firstPathChar);
                lastChar = firstPathChar;

                for (int i = pathStartIndex + 1; i < path.length(); i++) {
                    char current = path.charAt(i);
                    // 只有当当前字符和上一个字符不都是 '/' 时才追加
                    if (current != SLASH_CHAR || lastChar != SLASH_CHAR) {
                        sb.append(current);
                        lastChar = current;
                    }
                }
            }

            return sb.toString();
        });
    }


    /**
     * 去除路径中所有重复的 '/'，并去除开头的 '/'。
     *
     * <p>先调用 {@link #removeDuplicateSlashes(String)} 压缩重复斜杠，
     * 再调用 {@link #trimStartSlashes(String)} 去除开头斜杠。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  处理后的路径。
     *          如 path = '/folder1//file1/' 返回 'folder1/file1/'；
     *          如 path = '///folder1////file1///' 返回 'folder1/file1/'。
     */
    public static String removeDuplicateSlashesAndTrimStart(String path) {
        path = removeDuplicateSlashes(path);
        path = trimStartSlashes(path);
        return path;
    }


    /**
     * 去除路径中所有重复的 '/'，并去除结尾的 '/'。
     *
     * <p>先调用 {@link #removeDuplicateSlashes(String)} 压缩重复斜杠，
     * 再调用 {@link #trimEndSlashes(String)} 去除结尾斜杠。</p>
     *
     * @param   path
     *          路径字符串
     *
     * @return  处理后的路径。
     *          如 path = '/folder1//file1/' 返回 '/folder1/file1'；
     *          如 path = '///folder1////file1///' 返回 '/folder1/file1'。
     */
    public static String removeDuplicateSlashesAndTrimEnd(String path) {
        path = removeDuplicateSlashes(path);
        path = trimEndSlashes(path);
        return path;
    }


    /**
     * 拼接多个字符串为 URL 路径，并去除重复的 '/' 和开头的 '/'。
     *
     * <p>该方法不会影响 http:// 和 https:// 协议头中的 <code>://</code>。
     * 内部先调用 {@link #concat(String...)} 拼接字符串，再用 {@link #trimStartSlashes(String)} 去除开头斜杠。</p>
     *
     * @param   strs
     *          待拼接的字符串数组
     *
     * @return  拼接并处理后的路径字符串。
     */
    public static String concatTrimStartSlashes(String... strs) {
        return trimStartSlashes(concat(strs));
    }


    /**
     * 拼接多个字符串为 URL 路径，并去除重复的 '/' 和结尾的 '/'。
     *
     * <p>该方法不会影响 http:// 和 https:// 协议头中的 <code>://</code>。
     * 内部先调用 {@link #concat(String...)} 拼接字符串，再用 {@link #trimEndSlashes(String)} 去除结尾斜杠。</p>
     *
     * @param   strs
     *          待拼接的字符串数组
     *
     * @return  拼接并处理后的路径字符串。
     */
    public static String concatTrimEndSlashes(String... strs) {
        return trimEndSlashes(concat(strs));
    }


    /**
     * 拼接多个字符串为 URL 路径，并去除重复的 '/' 以及开头和结尾的 '/'。
     *
     * <p>该方法不会影响 http:// 和 https:// 协议头中的 <code>://</code>。
     * 内部先调用 {@link #concat(String...)} 拼接字符串，再用 {@link #trimSlashes(String)} 去除首尾斜杠。</p>
     *
     * @param   strs
     *          待拼接的字符串数组
     *
     * @return  拼接并处理后的路径字符串。
     */
    public static String concatTrimSlashes(String... strs) {
        return trimSlashes(concat(strs));
    }


    /**
     * 拼接多个字符串为 URL 路径，并去除重复的 '/'。
     *
     * <p>该方法使用 '/' 作为分隔符拼接各个字符串片段，跳过空字符串，
     * 最后调用 {@link #removeDuplicateSlashes(String)} 去除连续重复的斜杠。
     * 注意：http:// 和 https:// 协议头中的 <code>://</code> 不受影响。</p>
     *
     * @param   strs
     *          待拼接的字符串数组
     *
     * @return  拼接并去除重复斜杠后的路径字符串。
     */
    public static String concat(String... strs) {
        // 以 '/' 开头，确保拼接结果在去除重复斜杠后能正确以 '/' 开头
        StringBuilder sb = new StringBuilder(SLASH);
        for (int i = 0; i < strs.length; i++) {
            String str = strs[i];
            // 跳过空字符串，避免产生多余的 '/'
            if (isEmpty(str)) {
                continue;
            }
            sb.append(str);
            // 非最后一个元素时追加分隔符 '/'
            if (i != strs.length - 1) {
                sb.append(SLASH_CHAR);
            }
        }
        return removeDuplicateSlashes(sb.toString());
    }


    /**
     * 拼接多个字符串为 URL 路径，并可选择是否对结果进行 URL 编码（忽略 '/' 字符）。
     *
     * <p>先调用 {@link #concat(String...)} 拼接字符串并去除重复斜杠，
     * 如果 <code>encodeAllIgnoreSlashes</code> 为 true，则额外进行 URL 编码。</p>
     *
     * @param   encodeAllIgnoreSlashes
     *          是否对拼接结果进行 URL 编码（编码时忽略 '/' 字符，即保留斜杠不编码）
     *
     * @param   strs
     *          待拼接的字符串数组
     *
     * @return  拼接后的路径字符串（可能经过编码）。
     */
    public static String concat(boolean encodeAllIgnoreSlashes, String... strs) {
        String res = concat(strs);
        if (encodeAllIgnoreSlashes) {
            // 对非 '/' 部分进行全量 URL 编码
            return encodeAllIgnoreSlashes(res);
        } else {
            return res;
        }
    }


    /**
     * 替换 URL 中的 Host（主机名+端口）部分。
     *
     * <p>例如将 <code>http://a.com/1.txt</code> 中的 host 替换为 <code>https://abc.com</code>，
     * 得到 <code>https://abc.com/1.txt</code>。</p>
     *
     * <p>内部通过 {@link URL#getFile()} 提取原始 URL 的路径部分，
     * 然后与新的 host 进行拼接。</p>
     *
     * @param   originUrl
     *          原始 URL 地址
     *
     * @param   replaceHost
     *          替换后的 Host 地址（可以包含协议）
     *
     * @return  替换 Host 后的 URL；如果原始 URL 格式不正确则返回 null。
     */
    public static String replaceHost(String originUrl, String replaceHost) {
        try {
            // 提取原始 URL 的路径部分（包含查询参数等）
            String path = new URL(originUrl).getFile();
            // 将新的 Host 与原路径拼接
            return concat(replaceHost, path);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 对 URL 进行编码，默认使用 UTF-8 字符集。
     *
     * <p>该方法使用 Fragment 编码规则，允许的字符包括：
     * ALPHA、DIGIT、"-"、"."、"\_"、"~"、"/"、"?"、":"、"@"、"!"、"$"、"&amp;"、"'"、"("、")"、"*"、"+"、","、";"、"="。</p>
     *
     * <p>详细定义参见 RFC 3986 第 3.5 节：
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.5">RFC 3986 Section 3.5</a></p>
     *
     * @param   url
     *          待编码的 URL 字符串
     *
     * @return  编码后的字符串。
     */
    public static String encode(String url) {
        return URLEncodeUtil.encodeFragment(url);
    }


    /**
     * 对字符串进行全量 URL 编码，但保留 '/' 字符不编码。
     *
     * <p>该方法将字符串按 '/' 分段，对每一段分别进行全量编码，
     * 然后重新用 '/' 拼接。这样可以确保路径分隔符保持不变，
     * 而路径中的特殊字符（如中文、空格等）被正确编码。</p>
     *
     * @param   str
     *          待编码的字符串
     *
     * @return  编码后的字符串（'/' 字符保持原样）。
     */
    public static String encodeAllIgnoreSlashes(String str) {
        if (isEmpty(str)) {
            return str;
        }

        StringBuilder sb = new StringBuilder();

        int prevIndex = -1;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // 遇到 '/' 时，对前一段子串进行编码，然后追加 '/' 本身
            if (c == StringUtils.SLASH_CHAR) {
                if (prevIndex < i) {
                    // 提取两个 '/' 之间的子串并进行全量编码
                    String substring = str.substring(prevIndex + 1, i);
                    sb.append(URLEncodeUtil.encodeAll(substring));
                    prevIndex = i;
                }
                sb.append(c);
            }

            // 处理最后一个片段（结尾没有 '/' 的情况）
            if (i == str.length() - 1 && prevIndex < i) {
                String substring = str.substring(prevIndex + 1, i + 1);
                sb.append(URLEncodeUtil.encodeAll(substring));
            }
        }

        return sb.toString();
    }


    /**
     * 对 URL 进行解码，默认使用 UTF-8 字符集。
     *
     * <p>注意：该方法不会将 '+' 转为空格（即不进行 application/x-www-form-urlencoded 格式的解码）。</p>
     *
     * @param   url
     *          待解码的 URL 字符串
     *
     * @return  解码后的字符串。
     */
    public static String decode(String url) {
        return URLUtil.decode(url, StandardCharsets.UTF_8, false);
    }


    /**
     * 移除字符串中所有的换行符（包括 \r、\n 等），并去除前后空格。
     *
     * @param   str
     *          待处理的字符串
     *
     * @return  移除所有换行符并去除前后空格后的字符串。
     */
    public static String removeAllLineBreaksAndTrim(String str) {
        // 先移除所有换行符
        String removeResult = StrUtil.removeAllLineBreaks(str);
        // 再去除前后空格
        return trim(removeResult);
    }


    /**
     * 移除字符串前后的空格。
     *
     * <p>如果字符串为 null，则直接返回 null。</p>
     *
     * @param   str
     *          待处理的字符串
     *
     * @return  移除前后空格后的字符串；如果 str 为 null 则返回 null。
     */
    public static String trim(final String str) {
        return str == null ? null : str.trim();
    }


    /**
     * 如果给定字符串不是以指定后缀结尾，则在尾部补充该后缀。
     *
     * <p>常用于确保路径或 URL 以特定后缀结尾，如确保路径以 '/' 结尾。</p>
     *
     * @param   str
     *          原始字符串
     *
     * @param   suffix
     *          要检查并补充的后缀
     *
     * @return  如果字符串已以后缀结尾则返回原字符串，否则返回补充了后缀的字符串。
     */
    public static String addSuffixIfNot(CharSequence str, CharSequence suffix) {
        if (isEmpty(str) || isEmpty(suffix)) {
            return str.toString();
        }

        if (str.toString().endsWith(suffix.toString())) {
            return str.toString();
        }

        return str.toString() + suffix;
    }

    /**
     * 判断字符串是否包含另一个字符串，忽略大小写。
     *
     * <p>特殊规则：如果两个参数都为 null，则返回 true，表示 null 包含 null。</p>
     *
     * @param   str
     *          被检测的字符串
     *
     * @param   testStr
     *          要查找的子字符串
     *
     * @return  如果 str 包含 testStr（忽略大小写）返回 true；如果两者都为 null 也返回 true。
     */
    public static boolean containsIgnoreCase(CharSequence str, CharSequence testStr) {
        if (null == str) {
            // 如果被检测字符串为 null，则仅当查找的字符串也为 null 时才认为"包含"
            return null == testStr;
        }
        return StrUtil.indexOfIgnoreCase(str, testStr) > -1;
    }


    /**
     * 查找指定字符在字符串中第一次出现的位置索引。
     *
     * <p>如果字符串为空，则返回 {@link #INDEX_NOT_FOUND}。</p>
     *
     * @param   str
     *          待查找的字符串
     *
     * @param   searchChar
     *          要查找的字符
     *
     * @return  字符在字符串中第一次出现的位置索引；如果未找到或字符串为空则返回 INDEX_NOT_FOUND。
     */
    public static int indexOf(String str, char searchChar) {
        if (isEmpty(str)) {
            return INDEX_NOT_FOUND;
        }
        return str.indexOf(searchChar);
    }


    /**
     * 将驼峰命名格式的字符串转换为下划线命名格式。
     *
     * <p>例如：</p>
     * <ul>
     *     <li><code>helloWorld</code> 转换为 <code>hello_world</code></li>
     *     <li><code>HelloWorld</code> 转换为 <code>hello_world</code></li>
     *     <li><code>XMLParser</code> 转换为 <code>x_m_l_parser</code></li>
     * </ul>
     *
     * <p>转换规则：遇到大写字母时，在其前面插入下划线，并将该字母转为小写。</p>
     *
     * @param   param
     *          驼峰格式的字符串
     *
     * @return  下划线格式的字符串；如果输入为空则返回空字符串。
     */
    public static String camelToUnderline(String param) {
        if (isEmpty(param)) {
            return EMPTY;
        }

        int len = param.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = param.charAt(i);
            // 遇到大写字母且不是第一个字符时，在前面插入下划线
            if (Character.isUpperCase(c) && i > 0) {
                sb.append(UNDERLINE);
            }
            // 将字符统一转为小写
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }


    /**
     * 强制给 URL 设置协议（schema）。
     *
     * <p>该方法会先移除 URL 中已有的协议部分（http:// 或 https://），
     * 然后拼接新的协议。比较时不区分大小写。</p>
     *
     * <p>示例：</p>
     * <pre>
     * setSchema("http://a.com/1.txt", "https")  =&gt; "https://a.com/1.txt"
     * setSchema("a.com/1.txt", "https://")       =&gt; "https://a.com/1.txt"
     * setSchema("HTTPS://a.com/1.txt", "http")   =&gt; "http://a.com/1.txt"
     * </pre>
     *
     * @param   url
     *          URL 地址，可以带协议也可以不带协议，比较时忽略大小写
     *
     * @param   schema
     *          要设置的协议，如 http、https、http://、https://
     *
     * @return  设置协议后的完整 URL；如果 url 或 schema 为空则返回原 url。
     */
    public static String setSchema(String url, String schema) {
        if (StringUtils.isEmpty(url) || StringUtils.isEmpty(schema)) {
            return url;
        }

        // 确保 schema 以 :// 结尾
        if (!schema.endsWith("://")) {
            schema += "://";
        }

        // 移除已有的协议部分（忽略大小写）
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.startsWith("http://")) {
            url = url.substring(7);
        } else if (lowerUrl.startsWith("https://")) {
            url = url.substring(8);
        }

        // 拼接新协议
        return schema + url;
    }

}