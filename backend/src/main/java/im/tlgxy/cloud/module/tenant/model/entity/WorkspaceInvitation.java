package im.tlgxy.cloud.module.tenant.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName("workspace_invitation")
public class WorkspaceInvitation {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer tenantId;
    private Integer issuerUserId;
    @JsonIgnore
    private String tokenHash;
    private Long createdAt;
    private Long expiresAt;
    private Integer maxUses;
    private Integer useCount;
    private Boolean revoked;
}
