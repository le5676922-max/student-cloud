package im.tlgxy.cloud.module.ai.model.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName("ai_schedule")
public class AgentSchedule {
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer ownerUserId;
    private String title;
    private String taskType;
    private String storageKey;
    private String directoryPath;
    private Integer courseId;
    private String tags;
    private Integer dayOfWeek;
    private Integer hourOfDay;
    private Integer minuteOfHour;
    private Boolean enabled;
    private Long nextRunAt;
    private Long lastRunAt;
    private String lastError;
    private Integer retryCount;
    private Long retryAt;
    @JsonIgnore
    private String claimToken;
    private Long claimUntil;
    @JsonIgnore
    private String occurrenceTaskId;
    private String lastTaskId;
    @TableField(exist = false)
    private String previewState;
}
