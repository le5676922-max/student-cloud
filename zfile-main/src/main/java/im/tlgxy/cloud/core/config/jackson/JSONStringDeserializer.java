package im.tlgxy.cloud.core.config.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * JSON 字符串反序列化器。
 * <p>
 * 继承自 Jackson 的 {@link JsonDeserializer}，用于将数据库或前端传入的 JSON 格式字符串
 * 反序列化为 Java 对象。此反序列化器会将 JSON 字符串中的 JSON 节点读取出来，
 * 然后通过 {@link ObjectMapper} 重新序列化为标准 JSON 字符串返回。
 * </p>
 * <p>
 * 主要用途：当数据库字段存储的是 JSON 字符串（如 VARCHAR 类型），需要将其反序列化
 * 为 Java 对象时，使用此反序列化器可以保持 JSON 字符串的原始格式。
 * </p>
 *
 * @author zhaojun
 */
public class JSONStringDeserializer extends JsonDeserializer<String> {

    /**
     * 将 JSON 解析器当前位置的内容反序列化为字符串。
     * <p>
     * 流程：先从 JsonParser 中读取 JSON 节点树，然后通过 ObjectMapper 将其
     * 写回为标准 JSON 字符串，确保 JSON 格式的正确性和一致性。
     * </p>
     *
     * @param p       JSON 解析器，指向当前要解析的 JSON 内容位置
     * @param context 反序列化上下文，提供反序列化过程中的配置信息
     * @return 反序列化后的 JSON 字符串
     * @throws IOException 读取或写入 JSON 时发生的 IO 异常
     */
    @Override
    public String deserialize(JsonParser p, DeserializationContext context) throws IOException {
        // 从 JSON 解析器中读取整个 JSON 节点树
        JsonNode node = p.getCodec().readTree(p);
        // 创建 ObjectMapper 实例，用于将 JSON 节点树重新序列化为字符串
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(node);
    }

}
