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
    private Integer indexVersion;
    private Integer coveredChars;
    private Integer indexedPages;
    private Integer totalPages;
    private Boolean truncated;
    private String indexNotice;
    @JsonIgnore
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String embeddingJson;
    @JsonIgnore
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String embeddingSignature;
    private Integer semanticSegments;
    private String semanticNotice;
}
