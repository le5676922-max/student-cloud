package im.tlgxy.cloud.core.config.spring;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Jackson 枚举反序列化器。
 * <p>
 * 用于将前端请求中的字符串参数自动转换为对应的 Java 枚举对象。
 * 与 {@link StringToEnumConverterFactory} 配合使用，支持两种取值方式的枚举：
 * </p>
 * <ul>
 *   <li>实现了 {@code IEnum} 接口的枚举 —— 通过 {@code getValue()} 方法获取枚举值。</li>
 *   <li>标有 {@code @EnumValue} 注解的字段 —— 通过反射调用对应的 getter 方法获取枚举值。</li>
 * </ul>
 * <p>
 * 同时实现了 {@link ContextualDeserializer} 接口，可以根据目标枚举类型动态创建
 * 对应的反序列化器实例，实现类型安全的上下文反序列化。
 * </p>
 *
 * @author zhaojun
 */
@Setter
@Slf4j
// @JsonComponent 注解使其能够被 Spring Boot 自动注册到 Jackson 的 ObjectMapper 中
@JsonComponent
public class JacksonEnumDeserializer extends JsonDeserializer<Enum<?>> implements ContextualDeserializer {

    /**
     * 当前反序列化器对应的目标枚举类
     */
    private Class<?> clazz;

    /**
     * 反序列化操作：将 JSON 中的字符串值转换为对应的枚举常量。
     * <p>
     * 通过反射调用枚举的取值方法（IEnum 的 getValue() 或 @EnumValue 标注的字段的 getter 方法），
     * 与传入的字符串进行比对，找到匹配的枚举常量并返回。
     * </p>
     *
     * @param jsonParser JSON 解析器，指向当前要解析的 JSON 内容位置
     * @param ctx        反序列化上下文
     * @return 匹配的枚举常量，如果找不到则返回 null
     * @throws IOException 读取 JSON 时可能发生的 IO 异常
     */
    @Override
    public Enum<?> deserialize(JsonParser jsonParser, DeserializationContext ctx) throws IOException {
        Class<?> enumType = clazz;
        // 如果目标类型为空或不是枚举类型，直接返回 null
        if (Objects.isNull(enumType) || !enumType.isEnum()) {
            return null;
        }
        // 获取传入的字符串值
        String text = jsonParser.getText();
        // 获取该枚举类的取值方法（可能是 getValue() 或 @EnumValue 注解字段的 getter）
        Method method = StringToEnumConverterFactory.getMethod(clazz);
        // 获取该枚举类的所有枚举常量
        Enum<?>[] enumConstants = (Enum<?>[]) enumType.getEnumConstants();

        // 遍历所有枚举常量，将取值方法返回的值与传入字符串比对
        for (Enum<?> e : enumConstants) {
            try {
                if (Objects.equals(method.invoke(e).toString(), text)) {
                    return e;
                }
            } catch (IllegalAccessException | InvocationTargetException ex) {
                log.error("获取枚举值错误!!! ", ex);
            }
        }
        return null;
    }


    /**
     * 为不同的目标枚举类型创建上下文相关的反序列化器。
     * <p>
     * Jackson 在反序列化某个枚举类型的属性时，会调用此方法创建一个针对该枚举类型
     * 的反序列化器实例，并通过 setClazz 方法注入目标类型信息。
     * </p>
     *
     * @param ctx      反序列化上下文，包含目标类型信息
     * @param property 当前正在反序列化的 Bean 属性描述
     * @return 配置了目标枚举类型的反序列化器实例
     */
    @Override
    public JsonDeserializer<Enum<?>> createContextual(DeserializationContext ctx, BeanProperty property) {
        // 从上下文获取反序列化目标的原始类型
        Class<?> rawCls = ctx.getContextualType().getRawClass();
        // 创建新的反序列化器实例并设置目标枚举类型
        JacksonEnumDeserializer converter = new JacksonEnumDeserializer();
        converter.setClazz(rawCls);
        return converter;
    }

}
