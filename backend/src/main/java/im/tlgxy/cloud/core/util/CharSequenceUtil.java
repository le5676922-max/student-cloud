package im.tlgxy.cloud.core.util;

import cn.hutool.core.text.StrSplitter;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * 字符串（CharSequence）工具类
 * <p>
 * 提供字符串相关的通用工具方法，包括空值判断、相等比较、切分、拼接、前后缀匹配等操作。
 * 该类实现了 {@link CharPool} 接口，可直接使用其中定义的字符常量。
 * </p>
 * <p>
 * 大部分方法接受 {@link CharSequence} 接口类型，兼容 String、StringBuilder、StringBuffer 等多种实现。
 * 参数标注了 {@link Nullable} 的方法支持传入 {@code null} 值。
 * </p>
 *
 * @author zhaojun
 */
public class CharSequenceUtil implements CharPool {

    /**
     * 未找到索引时的返回值常量
     */
    public static final int INDEX_NOT_FOUND = -1;

    /**
     * 字符串常量：{@code "null"}
     * <p>注意：字符串 {@code "null"} 与 Java 的 {@code null} 引用是不同的概念。</p>
     */
    public static final String NULL = "null";

    /**
     * 字符串常量：空字符串 {@code ""}
     */
    public static final String EMPTY = "";

    /**
     * 字符串常量：空格符 {@code " "}
     */
    public static final String SPACE = " ";


    /**
     * 获取 CharSequence 的长度
     * <p>如果传入 {@code null}，返回 0 而不是抛出空指针异常。</p>
     *
     * @param ch 要获取长度的 CharSequence，可能为 {@code null}
     * @return CharSequence 的长度；若为 {@code null} 则返回 0
     */
    public static int length(final @Nullable CharSequence ch) {
        return ch == null ? 0 : ch.length();
    }


    /**
     * 将 {@link CharSequence} 转为 String
     * <p>如果传入 {@code null}，返回 {@code null}。</p>
     *
     * @param cs {@link CharSequence} 对象，可能为 {@code null}
     * @return 转换后的字符串；若参数为 {@code null} 则返回 {@code null}
     */
    public static String str(final @Nullable CharSequence cs) {
        return null == cs ? null : cs.toString();
    }


    /**
     * 判断 CharSequence 是否为空
     * <p>当字符串为 {@code null} 或长度为 0 时，视为空。</p>
     *
     * @param cs {@link CharSequence} 对象，可能为 {@code null}
     * @return 如果为空返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isEmpty(final @Nullable CharSequence cs) {
        return cs == null || cs.isEmpty();
    }


    /**
     * 判断 CharSequence 是否不为空
     * <p>与 {@link #isEmpty(CharSequence)} 结果相反。</p>
     *
     * @param cs {@link CharSequence} 对象，可能为 {@code null}
     * @return 如果不为空返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isNotEmpty(final @Nullable CharSequence cs) {
        return !isEmpty(cs);
    }


    /**
     * 判断指定字符串数组中的元素是否全部为空
     * <p>
     * 如果字符串数组为 {@code null}、长度为 0，或者所有元素都是空字符串（{@code null} 或 {@code ""}），
     * 则返回 {@code true}。
     * </p>
     * <p>示例：</p>
     * <ul>
     *     <li>{@code CharSequenceUtil.isAllEmpty()                  // true}</li>
     *     <li>{@code CharSequenceUtil.isAllEmpty("", null)          // true}</li>
     *     <li>{@code CharSequenceUtil.isAllEmpty("123", "")         // false}</li>
     *     <li>{@code CharSequenceUtil.isAllEmpty("123", "abc")      // false}</li>
     *     <li>{@code CharSequenceUtil.isAllEmpty(" ", "\t", "\n")   // false}</li>
     * </ul>
     *
     * @param strs 要检查的字符串列表，可能为 {@code null}
     * @return 如果所有字符串都为空，返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isAllEmpty(final @Nullable CharSequence... strs) {
        if (strs == null) {
            return true;
        }

        for (CharSequence str : strs) {
            if (isNotEmpty(str)) {
                return false; // 只要有一个不为空，就返回 false
            }
        }
        return true;
    }

    /**
     * 判断字符串数组中是否包含空字符串
     * <p>
     * 如果字符串数组为 {@code null}、长度为 0，或者其中任意一个元素是空字符串
     * （{@code null} 或 {@code ""}），则返回 {@code true}。
     * </p>
     * <p>示例：</p>
     * <ul>
     *     <li>{@code CharSequenceUtil.hasEmpty()                  // true}</li>
     *     <li>{@code CharSequenceUtil.hasEmpty("", null)          // true}</li>
     *     <li>{@code CharSequenceUtil.hasEmpty("123", "")         // true}</li>
     *     <li>{@code CharSequenceUtil.hasEmpty("123", "abc")      // false}</li>
     *     <li>{@code CharSequenceUtil.hasEmpty(" ", "\t", "\n")   // false}</li>
     * </ul>
     *
     * @param strs 要检查的字符串列表，可能为 {@code null}
     * @return 如果包含空字符串，返回 {@code true}；否则返回 {@code false}
     */
    public static boolean hasEmpty(final @Nullable CharSequence... strs) {
        if (ArrayUtils.isEmpty(strs)) {
            return true;
        }

        for (CharSequence str : strs) {
            if (isEmpty(str)) {
                return true; // 只要有一个为空，就返回 true
            }
        }
        return false;
    }


    /**
     * 判断字符串数组中的元素是否都不为空
     * <p>
     * 与 {@link #hasEmpty(CharSequence...)} 结果相反——如果数组不为 {@code null} 且所有元素都不为空，
     * 则返回 {@code true}。
     * </p>
     * <p>示例：</p>
     * <ul>
     *     <li>{@code CharSequenceUtil.isAllNotEmpty()                  // false}</li>
     *     <li>{@code CharSequenceUtil.isAllNotEmpty("", null)          // false}</li>
     *     <li>{@code CharSequenceUtil.isAllNotEmpty("123", "")         // false}</li>
     *     <li>{@code CharSequenceUtil.isAllNotEmpty("123", "abc")      // true}</li>
     *     <li>{@code CharSequenceUtil.isAllNotEmpty(" ", "\t", "\n")   // true}</li>
     * </ul>
     *
     * @param args 要检查的字符串数组，可能为 {@code null}
     * @return 如果所有字符串都不为空，返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isAllNotEmpty(final @Nullable CharSequence... args) {
        return !hasEmpty(args);
    }


    /**
     * 判断字符串是否为空白
     * <p>
     * 空白的定义：字符串为 {@code null}、空字符串 {@code ""}、或只包含空白字符
     * （空格、制表符、换行符等通过 {@link Character#isWhitespace(char)} 判断的字符）。
     * </p>
     *
     * @param ch 要判断的字符串，可能为 {@code null}
     * @return 如果为空白返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isBlank(final @Nullable CharSequence ch) {
        // 先判断长度，避免空指针
        final int strLen = ch == null ? 0 : ch.length();
        if (strLen == 0) {
            return true;
        }
        // 逐个字符检查是否为空白字符
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(ch.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 判断字符串是否不为空白
     * <p>与 {@link #isBlank(CharSequence)} 结果相反。</p>
     *
     * @param cs 要判断的字符串，可能为 {@code null}
     * @return 如果不为空白返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isNotBlank(final @Nullable CharSequence cs) {
        return !isBlank(cs);
    }


    /**
     * 比较两个 CharSequence 是否相等（区分大小写）
     * <p>
     * 如果两个参数引用相同（包括都为 {@code null}），返回 {@code true}。
     * 如果其中一个为 {@code null} 另一个不为 {@code null}，返回 {@code false}。
     * 比较时先比较长度，如果长度不同则直接返回 {@code false}，再逐个字符比较。
     * </p>
     *
     * @param cs1 第一个 CharSequence，可能为 {@code null}
     * @param cs2 第二个 CharSequence，可能为 {@code null}
     * @return 如果两个 CharSequence 相等返回 {@code true}，否则返回 {@code false}
     */
    public static boolean equals(final @Nullable CharSequence cs1, final @Nullable CharSequence cs2) {
        if (cs1 == cs2) {
            return true; // 两个引用相同（包括都为 null 的情况）
        }
        if (cs1 == null || cs2 == null) {
            return false; // 一个为 null，另一个不为 null
        }
        if (cs1.length() != cs2.length()) {
            return false; // 长度不同，不可能相等
        }
        if (cs1 instanceof String && cs2 instanceof String) {
            return cs1.equals(cs2); // 两个都是 String，直接调用 String.equals()
        }
        // 都不是 String 或其中一个是，逐个字符比较
        final int length = cs1.length();
        for (int i = 0; i < length; i++) {
            if (cs1.charAt(i) != cs2.charAt(i)) {
                return false;
            }
        }
        return true;
    }


    /**
     * 比较两个 CharSequence 是否相等，可选择是否忽略大小写
     * <p>
     * 如果 {@code ignoreCase} 为 {@code true}，则调用 {@link #equalsIgnoreCase(CharSequence, CharSequence)}；
     * 否则调用 {@link #equals(CharSequence, CharSequence)}。
     * </p>
     *
     * @param cs1       第一个 CharSequence，可能为 {@code null}
     * @param cs2       第二个 CharSequence，可能为 {@code null}
     * @param ignoreCase 是否忽略大小写
     * @return 如果两个 CharSequence 相等返回 {@code true}，否则返回 {@code false}
     */
    public static boolean equals(final @Nullable CharSequence cs1, final @Nullable CharSequence cs2, boolean ignoreCase) {
        return ignoreCase ? equalsIgnoreCase(cs1, cs2) : equals(cs1, cs2);
    }


    /**
     * 比较两个 CharSequence 是否相等（忽略大小写）
     * <p>
     * 内部通过 {@link String#equalsIgnoreCase(String)} 进行比较。
     * 如果两个参数引用相同（包括都为 {@code null}），返回 {@code true}。
     * </p>
     *
     * @param cs1 第一个 CharSequence，可能为 {@code null}
     * @param cs2 第二个 CharSequence，可能为 {@code null}
     * @return 如果忽略大小写后相等返回 {@code true}，否则返回 {@code false}
     */
    public static boolean equalsIgnoreCase(final @Nullable CharSequence cs1, final @Nullable CharSequence cs2) {
        if (cs1 == cs2) {
            return true; // 两个引用相同（包括都为 null 的情况）
        }
        if (cs1 == null || cs2 == null) {
            return false; // 一个为 null，另一个不为 null
        }
        if (cs1.length() != cs2.length()) {
            return false; // 长度不同，不可能相等
        }

        return cs1.toString().equalsIgnoreCase(cs2.toString());
    }


    /**
     * 切分字符串，默认不去除空格、不忽略空串
     * <p>如果分隔符不存在，则返回包含原字符串的列表。</p>
     *
     * @param str       被切分的字符串
     * @param separator 分隔符
     * @return 切分后的字符串列表
     */
    public static List<String> split(final CharSequence str, final CharSequence separator) {
        return split(str, separator, false, false);
    }


    /**
     * 切分字符串
     *
     * @param str         被切分的字符串
     * @param separator   分隔符
     * @param isTrim      是否去除切分后每个元素两边的空格
     * @param ignoreEmpty 是否忽略空串（长度为 0 的元素）
     * @return 切分后的字符串列表
     */
    public static List<String> split(CharSequence str, CharSequence separator, boolean isTrim, boolean ignoreEmpty) {
        return split(str, separator, 0, isTrim, ignoreEmpty);
    }


    /**
     * 切分字符串（完整参数版本）
     *
     * @param str         被切分的字符串
     * @param separator   分隔符
     * @param limit       限制分片数，0 或负数表示不限制
     * @param isTrim      是否去除切分后每个元素两边的空格
     * @param ignoreEmpty 是否忽略空串（长度为 0 的元素）
     * @return 切分后的字符串列表
     */
    public static List<String> split(CharSequence str, CharSequence separator, int limit, boolean isTrim, boolean ignoreEmpty) {
        final String separatorStr = (null == separator) ? null : separator.toString();
        return StrSplitter.split(str, separatorStr, limit, isTrim, ignoreEmpty);
    }


    /**
     * 判断指定字符串中是否包含另一个字符串
     * <p>如果任一参数为 {@code null}，直接返回 {@code false}。</p>
     *
     * @param str       被搜索的字符串，可能为 {@code null}
     * @param searchStr 要查找的字符串，可能为 {@code null}
     * @return 如果包含返回 {@code true}，否则返回 {@code false}
     */
    public static boolean contains(final @Nullable CharSequence str, final @Nullable CharSequence searchStr) {
        if (null == str || null == searchStr) {
            return false;
        }
        return str.toString().contains(searchStr);
    }


    /**
     * 判断指定字符串中是否包含指定字符串列表中的任意一个
     * <p>只要匹配到任意一个字符串就返回 {@code true}，区分大小写。</p>
     *
     * @param str      被搜索的字符串，可能为 {@code null}
     * @param testStrs 需要检查的字符串数组，可能为 {@code null}
     * @return 如果包含任意一个返回 {@code true}，否则返回 {@code false}
     */
    public static boolean containsAny(final @Nullable CharSequence str, final @Nullable CharSequence... testStrs) {
        if (isEmpty(str) || ArrayUtils.isEmpty(testStrs)) {
            return false;
        }
        for (CharSequence checkStr : testStrs) {
            if (null != checkStr && str.toString().contains(checkStr)) {
                return true; // 找到一个匹配的，立即返回 true
            }
        }
        return false;
    }


    /**
     * 判断指定字符串中是否包含指定字符串列表中的任意一个（忽略大小写）
     * <p>委托给 Apache Commons Lang3 的 {@link StringUtils#containsAnyIgnoreCase(CharSequence, CharSequence...)} 实现。</p>
     *
     * @param str      被搜索的字符串，可能为 {@code null}
     * @param testStrs 需要检查的字符串数组，可能为 {@code null}
     * @return 如果忽略大小写后包含任意一个返回 {@code true}，否则返回 {@code false}
     */
    public static boolean containsAnyIgnoreCase(final @Nullable CharSequence str, final @Nullable CharSequence... testStrs) {
        return StringUtils.containsAnyIgnoreCase(str, testStrs);
    }


    /**
     * 以指定分隔符将多个对象拼接为字符串
     * <p>遍历数组，在每个元素之间插入分隔符，不会在末尾添加多余的分隔符。</p>
     *
     * @param conjunction 分隔符
     * @param objs        要拼接的对象数组
     * @return 拼接后的字符串
     */
    public static String join(CharSequence conjunction, Object... objs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < objs.length; i++) {
            Object item = objs[i];
            sb.append(item);
            if (i < objs.length - 1) {
                sb.append(conjunction); // 最后一个元素后不添加分隔符
            }
        }
        return sb.toString();
    }


    /**
     * 以指定分隔符将集合对象拼接为字符串
     * <p>遍历集合，在每个元素之后都添加分隔符，最后删除末尾多余的分隔符。</p>
     *
     * @param conjunction 分隔符
     * @param collection  要拼接的集合
     * @return 拼接后的字符串
     */
    public static String join(CharSequence conjunction, Collection<?> collection) {
        StringBuilder sb = new StringBuilder();
        for (Object item : collection) {
            sb.append(item).append(conjunction);
        }
        if (!sb.isEmpty()) {
            // 删除末尾多余的分隔符
            sb.delete(sb.length() - conjunction.length(), sb.length());
        }
        return sb.toString();
    }


    /**
     * 判断字符串是否以指定前缀开头（区分大小写）
     *
     * @param str    被检测字符串
     * @param prefix 开头字符串
     * @return 如果以指定前缀开头返回 {@code true}，否则返回 {@code false}
     */
    public static boolean startWith(CharSequence str, CharSequence prefix) {
        return startWith(str, prefix, false);
    }


    /**
     * 判断字符串是否以指定前缀开头（忽略大小写）
     *
     * @param str    被检测字符串
     * @param prefix 开头字符串
     * @return 如果忽略大小写后以指定前缀开头返回 {@code true}，否则返回 {@code false}
     */
    public static boolean startWithIgnoreCase(CharSequence str, CharSequence prefix) {
        return startWith(str, prefix, true);
    }


    /**
     * 判断字符串是否以指定前缀开头
     * <p>
     * 如果被检测字符串和前缀都为 {@code null}，返回 {@code true}；
     * 否则任意一个值为 {@code null}，返回 {@code false}。
     * </p>
     *
     * @param str        被检测字符串，可能为 {@code null}
     * @param prefix     开头字符串，可能为 {@code null}
     * @param ignoreCase 是否忽略大小写
     * @return 如果以指定前缀开头返回 {@code true}，否则返回 {@code false}
     */
    public static boolean startWith(CharSequence str, CharSequence prefix, boolean ignoreCase) {
        return startWith(str, prefix, ignoreCase, false);
    }


    /**
     * 判断字符串是否以指定前缀开头（完整参数版本）
     * <p>
     * 如果被检测字符串和前缀都为 {@code null}，返回 {@code true}；
     * 否则任意一个值为 {@code null}，返回 {@code false}。
     * </p>
     * <p>示例：</p>
     * <pre>
     *     CharSequenceUtil.startWith("123", "123", false, true);   -- false
     *     CharSequenceUtil.startWith("ABCDEF", "abc", true, true); -- true
     *     CharSequenceUtil.startWith("abc", "abc", true, true);    -- false
     * </pre>
     *
     * @param str          被检测字符串，可能为 {@code null}
     * @param prefix       开头字符串，可能为 {@code null}
     * @param ignoreCase   是否忽略大小写
     * @param ignoreEquals 是否忽略完全相等的情况（为 {@code true} 时，两个完全相同的字符串返回 {@code false}）
     * @return 如果以指定前缀开头返回 {@code true}，否则返回 {@code false}
     */
    public static boolean startWith(final @Nullable CharSequence str, final @Nullable CharSequence prefix, boolean ignoreCase, boolean ignoreEquals) {
        if (null == str || null == prefix) {
            if (ignoreEquals) {
                return false;
            }
            return null == str && null == prefix; // 两个都为 null 才返回 true
        }

        // 使用 regionMatches 进行前缀匹配，支持忽略大小写
        boolean isStartWith = str.toString()
                .regionMatches(ignoreCase, 0, prefix.toString(), 0, prefix.length());

        if (isStartWith) {
            // 如果设置了 ignoreEquals，且两个字符串完全相等，则返回 false
            return (!ignoreEquals) || (!equals(str, prefix, ignoreCase));
        }
        return false;
    }


    /**
     * 判断字符串是否以指定后缀结尾（区分大小写）
     *
     * @param str    被检测字符串，可能为 {@code null}
     * @param suffix 结尾字符串，可能为 {@code null}
     * @return 如果以指定后缀结尾返回 {@code true}，否则返回 {@code false}
     */
    public static boolean endWith(final @Nullable CharSequence str, final @Nullable CharSequence suffix) {
        return endWith(str, suffix, false);
    }


    /**
     * 判断字符串是否以指定后缀结尾
     * <p>
     * 如果被检测字符串和后缀都为 {@code null}，返回 {@code true}；
     * 否则任意一个值为 {@code null}，返回 {@code false}。
     * </p>
     *
     * @param str        被检测字符串，可能为 {@code null}
     * @param suffix     结尾字符串，可能为 {@code null}
     * @param ignoreCase 是否忽略大小写
     * @return 如果以指定后缀结尾返回 {@code true}，否则返回 {@code false}
     */
    public static boolean endWith(final @Nullable CharSequence str, final @Nullable CharSequence suffix, boolean ignoreCase) {
        return endWith(str, suffix, ignoreCase, false);
    }


    /**
     * 判断字符串是否以指定后缀结尾（完整参数版本）
     * <p>
     * 如果被检测字符串和后缀都为 {@code null}，返回 {@code true}；
     * 否则任意一个值为 {@code null}，返回 {@code false}。
     * </p>
     *
     * @param str          被检测字符串，可能为 {@code null}
     * @param suffix       结尾字符串，可能为 {@code null}
     * @param ignoreCase   是否忽略大小写
     * @param ignoreEquals 是否忽略完全相等的情况（为 {@code true} 时，两个完全相同的字符串返回 {@code false}）
     * @return 如果以指定后缀结尾返回 {@code true}，否则返回 {@code false}
     */
    public static boolean endWith(final @Nullable CharSequence str, final @Nullable CharSequence suffix, boolean ignoreCase, boolean ignoreEquals) {
        if (null == str || null == suffix) {
            if (ignoreEquals) {
                return false;
            }
            return null == str && null == suffix;
        }

        // 计算从哪个位置开始比较后缀
        final int strOffset = str.length() - suffix.length();
        boolean isEndWith = str.toString()
                .regionMatches(ignoreCase, strOffset, suffix.toString(), 0, suffix.length());

        if (isEndWith) {
            // 如果设置了 ignoreEquals，且两个字符串完全相等，则返回 false
            return (!ignoreEquals) || (!equals(str, suffix, ignoreCase));
        }
        return false;
    }


    /**
     * 去掉字符串的指定前缀
     * <p>如果字符串不以指定前缀开头，则返回原字符串。</p>
     *
     * @param str    字符串，可能为 {@code null}
     * @param prefix 要去掉的前缀，可能为 {@code null}
     * @return 去掉前缀后的字符串；如果前缀不存在则返回原字符串
     */
    public static String removePrefix(final @Nullable CharSequence str, final @Nullable CharSequence prefix) {
        if (isEmpty(str) || isEmpty(prefix)) {
            return str(str);
        }
        String str2 = str.toString();
        String prefix2 = prefix.toString();
        if (str2.startsWith(prefix2)) {
            // 截取前缀之后的部分
            return str.subSequence(prefix.length(), str.length()).toString();
        }
        return str2; // 前缀不匹配，返回原字符串
    }


    /**
     * 从多个元素中返回第一个非 {@code null} 且非空的元素
     * <p>遍历给定的元素数组，返回第一个不为 {@code null} 且不为空字符串的元素。</p>
     *
     * @param <T>  元素类型，必须是 {@link CharSequence} 的子类型
     * @param strs 多个元素，可能包含 {@code null}
     * @return 第一个非空元素；如果所有元素都为空，返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T extends CharSequence> T firstNonNull(T... strs) {
        if (ArrayUtils.isNotEmpty(strs)) {
            for (T str : strs) {
                if (isNotEmpty(str)) {
                    return str; // 找到第一个非空的元素
                }
            }
        }
        return null;
    }


    /**
     * 截取分隔字符串之前的子串（不包括分隔字符串本身）
     * <p>
     * 如果被查找的字符串为空（{@code null} 或 {@code ""}）或分隔字符串为 {@code null}，
     * 返回原字符串。如果分隔字符串为空串 {@code ""}，返回空串。
     * 如果分隔字符串未找到，返回原字符串。
     * </p>
     * <p>示例：</p>
     * <pre>
     * CharSequenceUtil.subBefore(null, *, false)      = null
     * CharSequenceUtil.subBefore("", *, false)        = ""
     * CharSequenceUtil.subBefore("abc", "a", false)   = ""
     * CharSequenceUtil.subBefore("abcba", "b", false) = "a"
     * CharSequenceUtil.subBefore("abc", "c", false)   = "ab"
     * CharSequenceUtil.subBefore("abc", "d", false)   = "abc"
     * CharSequenceUtil.subBefore("abc", "", false)    = ""
     * CharSequenceUtil.subBefore("abc", null, false)  = "abc"
     * </pre>
     *
     * @param string          被查找的字符串，可能为 {@code null}
     * @param separator       分隔字符串（不包括在结果中）
     * @param isLastSeparator 是否查找最后一个分隔符：{@code true} 选取最后一次出现的位置，{@code false} 选取第一次出现的位置
     * @return 切割后的子串
     */
    public static String subBefore(final @Nullable CharSequence string, final @Nullable CharSequence separator, boolean isLastSeparator) {
        if (isEmpty(string) || separator == null) {
            return null == string ? null : string.toString();
        }

        final String str = string.toString();
        final String sep = separator.toString();
        if (sep.isEmpty()) {
            return EMPTY; // 分隔符为空串，返回空字符串
        }
        // 根据 isLastSeparator 决定查找第一个还是最后一个分隔符
        final int pos = isLastSeparator ? str.lastIndexOf(sep) : str.indexOf(sep);
        if (INDEX_NOT_FOUND == pos) {
            return str; // 未找到分隔符，返回原字符串
        }
        if (0 == pos) {
            return EMPTY; // 分隔符在开头，返回空字符串
        }
        return str.substring(0, pos); // 截取分隔符之前的部分
    }

}
