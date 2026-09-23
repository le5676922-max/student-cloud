package im.tlgxy.cloud.core.config.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;


/**
 * JSON 字符串序列化器。
 * <p>
 * 继承自 Jackson 的 {@link JsonSerializer}，用于将 Java 对象中的 JSON 字符串属性
 * 直接以原始 JSON 格式写入（非转义字符串），而不是作为普通字符串（被引号包裹并转义）。
 * </p>
 * <p>
 * 使用 {@link JsonGenerator#writeRawValue(String)} 方法直接将值作为原始 JSON 写入输出流，
 * 这样生成的 JSON 中该字段就是一个内嵌的 JSON 对象/数组，而非被转义的字符串。
 * </p>
 * <p>
 * 示例：如果 value 是 '{"name":"test"}'，序列化后得到的是嵌套 JSON 对象，而不是字符串
 * {"data": "{\"name\":\"test\"}"}。
 * </p>
 *
 * @author zhaojun
 */
public class JSONStringSerializer extends JsonSerializer<String> {

    /**
     * 将字符串值以原始 JSON 格式写入。
     * <p>
     * 使用 writeRawValue 而非 writeString，确保值作为原生 JSON 写入，
     * 不会被额外添加引号和进行转义处理。
     * </p>
     *
     * @param value       要序列化的 JSON 字符串值
     * @param gen         JSON 生成器，用于写入输出
     * @param serializers 序列化器提供者，提供序列化过程中的上下文信息
     * @throws IOException 写入过程中发生的 IO 异常
     */
    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        // 将字符串作为原始 JSON 值直接写入，不在外层添加引号和转义
        gen.writeRawValue(value);
    }

}
