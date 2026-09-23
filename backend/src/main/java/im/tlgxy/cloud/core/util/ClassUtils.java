package im.tlgxy.cloud.core.util;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Class 与反射相关工具类
 * <p>
 * 提供类加载、泛型类型获取等 Java 反射相关的工具方法。
 * 主要用于在运行时动态获取类的泛型参数类型。
 * </p>
 *
 * @author zhaojun
 */
public class ClassUtils {

    /**
     * 根据全限定类名加载 Class 对象
     * <p>如果类未找到，会抛出 {@link RuntimeException} 包装的 {@link ClassNotFoundException}。</p>
     *
     * @param className 类的全限定名（如 {@code "java.lang.String"}）
     * @return 对应的 Class 对象
     * @throws RuntimeException 如果找不到指定的类
     */
    public static Class<?> forName(String className) {
        try {
            return Class.forName(className); // 调用 JDK 的类加载机制
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定类的第一个泛型类型参数
     * <p>
     * 通过反射获取类的泛型父类，然后提取第一个泛型类型参数。
     * 例如：对于 {@code class Foo extends BaseService<User>}，
     * 调用 {@code getClassFirstGenericsParam(Foo.class)} 会返回 {@code User.class}。
     * </p>
     *
     * @param clazz 要获取泛型类型的类
     * @return 第一个泛型类型参数的 Class 对象
     * @throws ClassCastException            如果泛型类型不是 Class 类型
     * @throws java.lang.ClassCastException  如果父类不是参数化类型
     */
    public static Class<?> getClassFirstGenericsParam(Class<?> clazz) {
        // 获取带有泛型信息的父类 Type
        Type genericSuperclass = clazz.getGenericSuperclass();
        // 将父类 Type 强转为 ParameterizedType，以获取实际类型参数
        Type actualTypeArgument = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
        return (Class<?>) actualTypeArgument;
    }

    /**
     * 获取字段的泛型类型
     * <p>
     * 通过反射获取字段的泛型类型参数（如 {@code List<String>} 中的 {@code String}）。
     * 该方法仅获取第一个泛型类型参数。
     * </p>
     *
     * @param field 要获取泛型类型的字段
     * @return 第一个泛型类型参数的 Class 对象
     * @throws ClassCastException 如果字段的泛型类型不是参数化类型
     */
    public static Class<?> getGenericType(Field field) {
        // 获取字段的泛型类型（ParameterizedType）
        ParameterizedType listType = (ParameterizedType) field.getGenericType();
        // 提取第一个实际类型参数
        return (Class<?>) listType.getActualTypeArguments()[0];
    }

}
