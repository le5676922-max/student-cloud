package im.tlgxy.cloud.core.config.spring;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.IEnum;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import im.tlgxy.cloud.core.exception.core.SystemException;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * String 到枚举的通用转换器工厂。
 * <p>
 * 实现了 Spring 的 {@link ConverterFactory}{@code <String, Enum<?>>} 接口，
 * 用于将前端传递的字符串参数自动转换为对应的 Java 枚举对象。
 * 支持 MyBatis-Plus 的两种枚举定义方式：
 * </p>
 * <ul>
 *   <li><b>IEnum 接口方式：</b>实现了 {@code IEnum<?>} 接口的枚举，通过 {@code getValue()} 方法获取枚举值。</li>
 *   <li><b>@EnumValue 注解方式：</b>在枚举字段上标注 {@code @EnumValue} 注解，表示该字段为数据库存储值，
 *       通过对应的 getter 方法获取枚举值。</li>
 * </ul>
 * <p>
 * 内部维护了两个缓存（使用 {@link ConcurrentHashMap} 保证线程安全）：
 * </p>
 * <ul>
 *   <li><b>CONVERTER_MAP：</b>缓存已创建的转换器实例，避免重复创建。</li>
 *   <li><b>TABLE_METHOD_OF_ENUM_TYPES：</b>缓存枚举类的取值方法，避免重复反射。</li>
 * </ul>
 * <p>
 * 此工厂在 {@link WebMvcConfig#addFormatters} 中被注册到 Spring MVC，
 * 因此可以直接在 URL 参数或请求体中使用字符串值来表示枚举。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class StringToEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

    /**
     * 转换器缓存：存储每种枚举类型对应的 String -> Enum 转换器实例
     */
    private static final Map<Class<?>, Converter<String, ? extends Enum<?>>> CONVERTER_MAP = new ConcurrentHashMap<>();

    /**
     * 枚举取值方法缓存：存储每种枚举类型对应的取值方法（如 getValue() 或 @EnumValue 标注字段的 getter）
     */
    private static final Map<Class<?>, Method> TABLE_METHOD_OF_ENUM_TYPES = new ConcurrentHashMap<>();

    /**
     * 获取指定枚举类型对应的字符串转换器。
     * <p>
     * 先从缓存中查找，如果缓存不存在则创建新的转换器并缓存。
     * 每个 StringToEnumConverter 实例在构造时会预加载该枚举类的所有常量及其对应的字符串值映射。
     * </p>
     *
     * @param targetType 目标枚举类型
     * @param <T>        枚举类型
     * @return 该枚举类型的 String -> Enum 转换器
     */
    @Override
    @SuppressWarnings("unchecked cast")
    public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
        // 从缓存中查找已有的转换器
        Converter<String, T> converter = (Converter<String, T>) CONVERTER_MAP.get(targetType);
        if (converter == null) {
            // 缓存不存在，创建新的转换器并加入缓存
            converter = new StringToEnumConverter<>(targetType);
            CONVERTER_MAP.put(targetType, converter);
        }
        return converter;
    }

    /**
     * 字符串到枚举的内部转换器实现。
     * <p>
     * 在构造时会遍历枚举的所有常量，通过反射调用取值方法获取每个枚举的字符串表示，
     * 并建立字符串 -> 枚举常量的映射表，后续转换时直接查表即可。
     * </p>
     *
     * @param <T> 枚举类型
     */
    static class StringToEnumConverter<T extends Enum<?>> implements Converter<String, T> {

        /**
         * 字符串到枚举常量的映射表，如 "READ" -> PermissionEnum.READ
         */
        private final Map<String, T> enumMap = new ConcurrentHashMap<>();

        /**
         * 构造方法：根据传入的枚举类型，预加载所有枚举常量及其对应的字符串映射。
         *
         * @param enumType 枚举类型
         */
        StringToEnumConverter(Class<T> enumType) {
            // 获取该枚举类的取值方法
            Method method = getMethod(enumType);
            // 获取所有枚举常量
            T[] enums = enumType.getEnumConstants();

            // 遍历每个枚举常量，将其取值方法的结果作为 key，枚举常量本身作为 value，存入映射表
            for (T e : enums) {
                try {
                    enumMap.put(method.invoke(e).toString(), e);
                } catch (IllegalAccessException | InvocationTargetException ex) {
                    log.error("获取枚举值错误!!! ", ex);
                }
            }
        }


        /**
         * 将字符串转换为对应的枚举常量。
         *
         * @param source 源字符串
         * @return 匹配的枚举常量
         * @throws SystemException 当找不到匹配的枚举常量时抛出
         */
        @Override
        public T convert(@NotNull String source) {
            // 从映射表中查找匹配的枚举常量
            T t = enumMap.get(source);
            if (t == null) {
                throw new SystemException("该字符串找不到对应的枚举对象 字符串:" + source);
            }
            return t;
        }
    }


    /**
     * 获取指定枚举类型的取值方法。
     * <p>
     * 优先级：
     * </p>
     * <ol>
     *   <li>如果枚举实现了 {@code IEnum} 接口，则返回 {@code getValue()} 方法。</li>
     *   <li>否则在枚举字段中查找标有 {@code @EnumValue} 注解的字段，
     *       返回该字段对应的 getter 方法（如 boolean 类型的 isXxx，其他类型的 getXxx）。</li>
     * </ol>
     * <p>
     * 结果会被缓存在 TABLE_METHOD_OF_ENUM_TYPES 中，避免每次重复反射。
     * </p>
     *
     * @param enumType 枚举类型
     * @param <T>      枚举类型
     * @return 该枚举对应的取值方法
     */
    public static <T> Method getMethod(Class<T> enumType) {
        Method method;
        // 如果枚举实现了 IEnum 接口，使用 getValue() 方法获取枚举值
        if (IEnum.class.isAssignableFrom(enumType)) {
            try {
                method = enumType.getMethod("getValue");
            } catch (NoSuchMethodException e) {
                throw new SystemException(String.format("类:%s 找不到 getValue方法",
                        enumType.getName()));
            }
        } else {
            // 否则使用 @EnumValue 注解字段对应的 getter 方法
            method = TABLE_METHOD_OF_ENUM_TYPES.computeIfAbsent(enumType, k -> {
                // 查找标有 @EnumValue 注解的字段
                Field field =
                        dealEnumType(enumType).orElseThrow(() -> new IllegalArgumentException(String.format(
                                "类:%s 找不到 EnumValue注解", enumType.getName())));

                // 根据字段类型和字段名，拼接 getter 方法名
                Class<?> fieldType = field.getType();
                String fieldName = field.getName();
                // boolean 类型使用 "is" 前缀（如 isActive），其他类型使用 "get" 前缀（如 getCode）
                String methodName =  StringUtils.concatCapitalize(boolean.class.equals(fieldType) ? "is" : "get", fieldName);
                try {
                    return enumType.getDeclaredMethod(methodName);
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
                return null;
            });
        }
        return method;
    }


    /**
     * 处理枚举类型，查找其中标有 {@code @EnumValue} 注解的字段。
     *
     * @param clazz 类型 Class 对象
     * @return 包含 @EnumValue 注解的字段 Optional，没找到则返回 Optional.empty()
     */
    private static Optional<Field> dealEnumType(Class<?> clazz) {
        // 如果是枚举类，在所有声明字段中查找第一个标有 @EnumValue 注解的字段
        return clazz.isEnum() ?
                Arrays.stream(clazz.getDeclaredFields()).filter(field -> field.isAnnotationPresent(EnumValue.class)).findFirst() : Optional.empty();
    }

}
