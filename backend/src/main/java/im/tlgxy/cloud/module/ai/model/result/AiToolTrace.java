package im.tlgxy.cloud.module.ai.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Agent 调用站内工具后返回给前端的简要记录。 */
@Data
@AllArgsConstructor
public class AiToolTrace {

    /** 工具标识。 */
    private String name;

    /** 用户可读的执行摘要。 */
    private String summary;

    /** 工具是否执行成功。 */
    private boolean success;
}
