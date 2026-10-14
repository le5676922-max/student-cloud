package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import im.tlgxy.cloud.core.exception.core.BizException;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Reads real OpenAI-compatible deltas. Tool argument fragments are never executed before [DONE]. */
final class AiModelStream {
    private static final int MAX_STREAM_CHARS = 512_000;
    private static final int MAX_EVENT_CHARS = 64_000;

    static JsonNode read(Reader reader, ObjectMapper json, AiChatSessions.Session session,
                         Consumer<String> token) throws IOException {
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ObjectNode> calls = new TreeMap<>();
        int total = 0;
        boolean complete = false;
        for (int value; (value = reader.read()) != -1;) {
            session.check();
            if (++total > MAX_STREAM_CHARS) throw new BizException("AI 返回内容过长，请缩小问题范围");
            if (value != '\n') {
                line.append((char) value);
                if (line.length() > MAX_EVENT_CHARS) throw new BizException("AI 返回片段过长");
                continue;
            }
            String text = line.toString().replaceAll("\\r$", "");
            line.setLength(0);
            if (text.startsWith("data:")) {
                data.append(text.substring(5).stripLeading()).append('\n');
                if (data.length() > MAX_EVENT_CHARS) throw new BizException("AI 返回片段过长");
            } else if (text.isEmpty() && !data.isEmpty()) {
                String event = data.toString().trim();
                data.setLength(0);
                if ("[DONE]".equals(event)) { complete = true; break; }
                JsonNode payload = json.readTree(event);
                if (payload.has("error")) throw new BizException("AI 服务中断了生成，请稍后重试");
                JsonNode choices = payload.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue; // usage-only chunk
                JsonNode choice = choices.get(0);
                if (choice.path("index").asInt(0) != 0) continue;
                JsonNode delta = choice.path("delta");
                String fragment = delta.path("content").asText("");
                append(content, fragment, 40_000);
                if (!fragment.isEmpty()) token.accept(fragment);
                append(reasoning, delta.path("reasoning_content").asText(""), 64_000);
                JsonNode changes = delta.path("tool_calls");
                if (changes.isArray()) for (JsonNode change : changes) {
                    int index = change.path("index").asInt(-1);
                    if (index < 0 || index >= 12) throw new BizException("本轮工具调用过多，请缩小任务范围");
                    ObjectNode call = calls.computeIfAbsent(index, ignored -> {
                        ObjectNode node = json.createObjectNode();
                        node.put("type", "function");
                        node.set("function", json.createObjectNode());
                        return node;
                    });
                    append(call, "id", change.path("id").asText(""), 128);
                    ObjectNode function = (ObjectNode) call.get("function");
                    append(function, "name", change.path("function").path("name").asText(""), 128);
                    append(function, "arguments", change.path("function").path("arguments").asText(""), 16_000);
                }
            }
        }
        session.check();
        if (!complete) throw new BizException("AI 流式连接提前中断，未完成的内容仅供参考，请重试");
        ObjectNode assistant = json.createObjectNode().put("role", "assistant").put("content", content.toString());
        if (!reasoning.isEmpty()) assistant.put("reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) {
            for (ObjectNode call : calls.values()) {
                if (call.path("id").asText("").isBlank() || call.path("function").path("name").asText("").isBlank()) {
                    throw new BizException("AI 工具调用格式不完整");
                }
            }
            assistant.set("tool_calls", json.valueToTree(calls.values()));
        }
        return assistant;
    }

    private static void append(StringBuilder target, String text, int maximum) {
        if (target.length() + text.length() > maximum) throw new BizException("AI 返回内容过长，请缩小问题范围");
        target.append(text);
    }

    private static void append(ObjectNode target, String key, String fragment, int maximum) {
        String value = target.path(key).asText("") + fragment;
        if (value.length() > maximum) throw new BizException("AI 工具参数过长");
        target.put(key, value);
    }
}
