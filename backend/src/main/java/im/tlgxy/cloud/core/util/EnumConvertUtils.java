package im.tlgxy.cloud.core.util;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.lang.reflect.Field;

/**
 * 枚举转换工具类
 * <p>
 * 提供枚举对象与字符串/数值之间的双向转换功能。
 * 支持通过 {@link JsonValue} 和 {@link EnumValue} 注解来标识枚举的值字段，
 * 优先使用 {@link JsonValue} 注解的值。
 * </p>
 *
 * @author zhaojun
 */
public class EnumConvertUtils {


    /**
     * 根据枚举类和值获取对应的枚举对象
     * <p>
     * 遍历枚举类中所有标注了 {@link JsonValue} 或 {@link EnumValue} 注解的字段，
     * 查找字段值与给定 value 相等的枚举常量并返回。
     * 如果枚举类中没有任何字段标注这两个注解，则返回 null。
     * </p>
     *
     * @param   clazz
     *          枚举类的 Class 对象，如果不是枚举类型则返回 null
     *
     * @param   value
     *          要匹配的枚举值，可以是字符串或数值类型
     *
     * @return  匹配到的枚举对象，如果未找到则返回 null
     */
    public static Enum<?> convertStrToEnum(Class<?> clazz, Object value) {
        // 如果不是枚举类型，直接返回 null
        if (!ClassUtil.isEnum(clazz)) {
            return null;
        }

        // 使用反射获取枚举类中所有字段
        Field[] fields = ReflectUtil.getFields(clazz);
        for (Field field : fields) {
            // 检查字段是否标注了 JsonValue 或 EnumValue 注解
            boolean jsonValuePresent = field.isAnnotationPresent(JsonValue.class);
            boolean enumValuePresent = field.isAnnotationPresent(EnumValue.class);

            if (jsonValuePresent || enumValuePresent) {
                // 获取枚举类的所有常量
                Object[] enumConstants = clazz.getEnumConstants();

                // 遍历所有枚举常量，查找字段值与给定值相等的常量
                for (Object enumObj : enumConstants) {
                    if (ObjectUtil.equal(value, ReflectUtil.getFieldValue(enumObj, field))) {
                        return (Enum<?>) enumObj;
                    }
                }
            }
        }
        return null;
    }


    /**
     * 将枚举对象转换为字符串
     * <p>
     * 查找枚举对象中标注了 {@link JsonValue} 或 {@link EnumValue} 注解的字段，
     * 将该字段的值转换为字符串返回。优先使用 {@link JsonValue} 注解的字段值。
     * 如果枚举类中没有任何字段标注这两个注解，则返回 null。
     * </p>
     *
     * @param   enumObj
     *          枚举对象，如果不是枚举类型则返回 null
     *
     * @return  枚举值对应的字符串表示，如果未找到注解字段则返回 null
     */
    public static String convertEnumToStr(Object enumObj) {
        Class<?> clazz = enumObj.getClass();
        // 如果不是枚举类型，直接返回 null
        if (!ClassUtil.isEnum(clazz)) {
            return null;
        }

        // 使用反射获取枚举类中所有字段
        Field[] fields = ReflectUtil.getFields(clazz);
        for (Field field : fields) {
            // 检查字段是否标注了 JsonValue 或 EnumValue 注解
            boolean jsonValuePresent = field.isAnnotationPresent(JsonValue.class);
            boolean enumValuePresent = field.isAnnotationPresent(EnumValue.class);

            if (jsonValuePresent || enumValuePresent) {
                // 找到第一个标注了相关注解的字段，将其值转换为字符串返回
                return Convert.toStr(ReflectUtil.getFieldValue(enumObj, field));
            }
        }

        return null;
    }

}