package im.tlgxy.cloud.module.ai.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** AI Agent 对话结果。 */
@Data
@AllArgsConstructor
public class AiChatResult {

    /** Agent 返回给用户的文本。 */
    private String answer;

    /** 本轮对话中实际执行过的站内工具。 */
    private List<AiToolTrace> tools;
}
