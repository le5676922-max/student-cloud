package im.tlgxy.cloud.core.util;

/**
 * 字符串常量池接口
 * <p>
 * 定义了项目中常用的字符串常量，避免在代码中硬编码字符串字面量，提高代码可读性和可维护性。
 * 采用接口形式定义常量，实现类可以直接使用这些常量而无需加上类名前缀（通过静态导入或实现该接口）。
 * </p>
 * <p>
 * 包含以下类型的常量：
 * <ul>
 *   <li><b>符号字符</b>：点（.）、斜杠（/）、反斜杠（\）、下划线（_）、减号（-）、逗号（,）、冒号（:）、艾特（@）</li>
 *   <li><b>括号字符</b>：花括号（{}）、中括号（[]）</li>
 *   <li><b>空白/换行字符</b>：制表符（\t）、回车符（\r）、换行符（\n）、Windows 换行（\r\n）</li>
 *   <li><b>特殊字符串</b>：双点（..，用于上级目录）、空 JSON（{}）</li>
 * </ul>
 * </p>
 *
 * @author zhaojun
 */
public interface StrPool {

    /**
     * 字符串常量：制表符 {@code "\t"}
     */
    String TAB = "	";

    /**
     * 字符串常量：点 {@code "."}
     */
    String DOT = ".";

    /**
     * 字符串常量：双点 {@code ".."} <br>
     * 用途：作为指向上级文件夹的路径，如：{@code "../path"}
     */
    String DOUBLE_DOT = "..";

    /**
     * 字符串常量：斜杠 {@code "/"}
     */
    String SLASH = "/";

    /**
     * 字符串常量：反斜杠 {@code "\\"}
     */
    String BACKSLASH = "\\";

    /**
     * 字符串常量：回车符 {@code "\r"} <br>
     * 解释：该字符常用于表示 Linux 系统和 MacOS 系统下的文本换行
     */
    String CR = "\r";

    /**
     * 字符串常量：换行符 {@code "\n"}
     */
    String LF = "\n";

    /**
     * 字符串常量：Windows 换行 {@code "\r\n"} <br>
     * 解释：该字符串常用于表示 Windows 系统下的文本换行
     */
    String CRLF = "\r\n";

    /**
     * 字符串常量：下划线 {@code "_"}
     */
    String UNDERLINE = "_";

    /**
     * 字符串常量：减号（连接符） {@code "-"}
     */
    String DASHED = "-";

    /**
     * 字符串常量：逗号 {@code ","}
     */
    String COMMA = ",";

    /**
     * 字符串常量：花括号（左） <code>"{"</code>
     */
    String DELIM_START = "{";

    /**
     * 字符串常量：花括号（右） <code>"}"</code>
     */
    String DELIM_END = "}";

    /**
     * 字符串常量：中括号（左） {@code "["}
     */
    String BRACKET_START = "[";

    /**
     * 字符串常量：中括号（右） {@code "]"}
     */
    String BRACKET_END = "]";

    /**
     * 字符串常量：冒号 {@code ":"}
     */
    String COLON = ":";

    /**
     * 字符串常量：艾特 {@code "@"}
     */
    String AT = "@";

    /**
     * 字符串常量：空 JSON {@code "{}"}
     */
    String EMPTY_JSON = "{}";

}