package im.tlgxy.cloud.module.tenant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("workspace_join_request")
public class WorkspaceJoinRequest {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer tenantId;
    private Integer userId;
    private String status;
    private Long createdAt;
    private Long reviewedAt;
    private Integer reviewerUserId;
}
