package im.tlgxy.cloud.module.ai.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** AI Agent 对话请求。 */
@Data
public class AiChatRequest {

    /** 用户本次输入。 */
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息不能超过 2000 个字符")
    private String message;

    /** 最近的对话记录，由服务端再次截断，防止请求无限增长。 */
    @Valid
    @Size(max = 12, message = "最多携带 12 条历史消息")
    private List<ChatMessage> history = new ArrayList<>();

    /** 用户当前所在的前端路由，帮助 Agent 理解页面上下文。 */
    @Size(max = 255, message = "页面路径不能超过 255 个字符")
    @Pattern(
            regexp = "^/[A-Za-z0-9._~/%?=&:#-]*$",
            message = "页面路径必须是合法的站内路由"
    )
    private String pagePath;

    /** 单条历史消息。 */
    @Data
    public static class ChatMessage {

        @NotBlank(message = "消息角色不能为空")
        @Pattern(regexp = "user|assistant", message = "消息角色只能是 user 或 assistant")
        private String role;

        @NotBlank(message = "历史消息内容不能为空")
        @Size(max = 2000, message = "历史消息不能超过 2000 个字符")
        private String content;
    }
}
