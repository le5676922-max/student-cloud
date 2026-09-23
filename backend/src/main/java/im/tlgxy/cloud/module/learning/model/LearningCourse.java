package im.tlgxy.cloud.module.learning.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("learning_course")
public class LearningCourse {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer ownerUserId;
    private String name;
    private String semester;
}
