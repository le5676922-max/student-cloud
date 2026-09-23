package im.tlgxy.cloud.core.util;

/**
 * 数组工具类
 * <p>
 * 提供数组相关的通用工具方法，包括空值判断等操作。
 * 所有方法均为静态方法，无需实例化即可使用。
 * </p>
 *
 * @author zhaojun
 */
public class ArrayUtils {

    /**
     * 判断数组是否为空
     * <p>如果数组为 {@code null} 或数组长度为 0，则认为数组为空。</p>
     *
     * @param <T>   数组元素类型
     * @param array 要检查的数组，可能为 {@code null}
     * @return 如果数组为 {@code null} 或长度为 0，返回 {@code true}；否则返回 {@code false}
     */
    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * 判断数组是否不为空
     * <p>与 {@link #isEmpty(Object[])} 结果相反。</p>
     *
     * @param <T>   数组元素类型
     * @param array 要检查的数组，可能为 {@code null}
     * @return 如果数组不为 {@code null} 且长度大于 0，返回 {@code true}；否则返回 {@code false}
     */
    public static <T> boolean isNotEmpty(T[] array) {
        return !isEmpty(array);
    }

}
