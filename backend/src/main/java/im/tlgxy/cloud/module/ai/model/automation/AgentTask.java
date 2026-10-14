package im.tlgxy.cloud.module.ai.model.automation;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("ai_task")
public class AgentTask {
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer ownerUserId;
    private String title;
    private String status;
    private String stepsJson;
    private String resultsJson;
    private Long createdAt;
    private Long expiresAt;
}
