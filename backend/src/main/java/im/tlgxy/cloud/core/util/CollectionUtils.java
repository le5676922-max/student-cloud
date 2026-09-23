package im.tlgxy.cloud.core.util;

import cn.hutool.core.lang.func.Func1;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 集合工具类
 * <p>
 * 提供集合相关的通用工具方法，包括空值判断、元素获取、批量添加以及集合与 Map 的转换等操作。
 * 所有方法均为静态方法，无需实例化即可使用。
 * </p>
 *
 * @author zhaojun
 */
public class CollectionUtils {


    /**
     * 判断集合是否为空
     * <p>如果集合为 {@code null} 或不包含任何元素，则视为空。</p>
     *
     * @param collection 要检查的集合，可能为 {@code null}
     * @return 如果集合为 {@code null} 或为空，返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isEmpty(@Nullable Collection<?> collection) {
        return (collection == null || collection.isEmpty());
    }


    /**
     * 判断集合是否不为空
     * <p>与 {@link #isEmpty(Collection)} 结果相反。</p>
     *
     * @param collection 要检查的集合，可能为 {@code null}
     * @return 如果集合不为 {@code null} 且包含至少一个元素，返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isNotEmpty(@Nullable Collection<?> collection) {
        return !isEmpty(collection);
    }


    /**
     * 从集合中获取第一个元素
     * <p>如果集合为空或为 {@code null}，返回 {@code null}。</p>
     *
     * @param <T>  集合元素类型
     * @param list 集合，可能为 {@code null}
     * @return 第一个元素；如果集合为空则返回 {@code null}
     */
    @Nullable
    public static <T> T getFirst(@Nullable List<T> list) {
        if (isEmpty(list)) {
            return null;
        }
        return list.get(0);
    }


    /**
     * 从集合中获取最后一个元素
     * <p>如果集合为空或为 {@code null}，返回 {@code null}。</p>
     *
     * @param <T>  集合元素类型
     * @param list 集合，可能为 {@code null}
     * @return 最后一个元素；如果集合为空则返回 {@code null}
     */
    @Nullable
    public static <T> T getLast(@Nullable List<T> list) {
        if (isEmpty(list)) {
            return null;
        }
        return list.get(list.size() - 1);
    }


    /**
     * 将数组中的所有元素批量添加到集合中
     * <p>如果集合或数组为 {@code null}，则不执行任何操作，直接返回原集合。</p>
     *
     * @param <T>        集合元素类型
     * @param collection 目标集合 {@link Collection}
     * @param values     要添加的元素数组
     * @return 添加元素后的原集合（方便链式调用）
     */
    public static <T> Collection<T> addAll(Collection<T> collection, T[] values) {
        if (null != collection && null != values) {
            Collections.addAll(collection, values);
        }
        return collection;
    }


    /**
     * 将 {@link Iterable} 转换为 Map
     * <p>
     * 根据指定的 {@code keyFunc} 函数为每个元素生成 Key，Value 为元素本身。
     * 可以指定已有的 Map 作为结果容器，如不指定则默认创建一个新的 {@link HashMap}。
     * </p>
     * <p>
     * 如果 {@code values} 或 {@code keyFunc} 为 {@code null}，返回空的不可变 Map。
     * </p>
     *
     * @param <K>     Map Key 的类型
     * @param <V>     Map Value 的类型（即 Iterable 中元素的类型）
     * @param values  被转换的 Iterable，可能为 {@code null}
     * @param map     用于存放结果的 Map，如果为 {@code null} 则新建一个 {@link HashMap}
     * @param keyFunc 生成 Map Key 的函数，接收元素并返回对应的 Key
     * @return 转换后的 Map
     * @throws RuntimeException 如果 keyFunc 执行过程中抛出异常
     */
    public static <K, V> Map<K, V> toMap(final @Nullable Iterable<V> values, final @Nullable Map<K, V> map, final @Nullable Func1<V, K> keyFunc) {
        if (values == null || keyFunc == null) {
            return Collections.emptyMap();
        }

        // 如果指定了已有 Map 则使用，否则新建 HashMap
        final Map<K, V> result = map == null ? new HashMap<>() : map;

        for (V value : values) {
            try {
                // 通过 keyFunc 计算 Key，将元素自身作为 Value 放入 Map
                result.put(keyFunc.call(value), value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

}
