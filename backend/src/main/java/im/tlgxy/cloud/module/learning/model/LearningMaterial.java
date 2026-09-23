package im.tlgxy.cloud.module.learning.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.Date;

@Data
@TableName("learning_material")
public class LearningMaterial {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer ownerUserId;
    private Integer courseId;
    private String storageKey;
    private String path;
    private String name;
    private String tags;
    @JsonIgnore
    private String content;
    @JsonIgnore
    private String fingerprint;
    private Date indexedAt;
}
