package im.tlgxy.cloud.core.util;

/**
 * 数字工具类
 * <p>
 * 提供对 {@link Integer} 类型数值的判断工具方法，主要用于简化 null 判断和零值判断的组合逻辑。
 * 避免在业务代码中重复编写 {@code number != null && number != 0} 这样的样板代码。
 * <p>
 * 典型使用场景：
 * <ul>
 *     <li>判断存储空间配额是否为空或为 0（表示不限制）</li>
 *     <li>判断配置项的数值是否有效（非空且非零）</li>
 * </ul>
 *
 * @author zhaojun
 */
public class NumberUtils {

    /**
     * 判断指定的 Integer 数值是否为 null 或等于 0
     * <p>
     * 该方法将 null 和 0 视为等价——都表示"无值"或"未设置"的状态。
     *
     * @param number 待判断的 Integer 值，可以为 null
     * @return 如果 number 为 null 或值为 0，返回 true；否则返回 false
     */
    public static boolean isNullOrZero(Integer number) {
        return number == null || number == 0;
    }

    /**
     * 判断指定的 Integer 数值是否既不为 null 也不等于 0
     * <p>
     * 与 {@link #isNullOrZero(Integer)} 逻辑相反，用于判断数值是否"有效"。
     *
     * @param number 待判断的 Integer 值，可以为 null
     * @return 如果 number 不为 null 且值不为 0，返回 true；否则返回 false
     */
    public static boolean isNotNullOrZero(Integer number) {
        return number != null && number != 0;
    }

}