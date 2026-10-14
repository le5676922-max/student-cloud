package im.tlgxy.cloud.module.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkspaceInvitationMapper extends BaseMapper<WorkspaceInvitation> {
    @Update("UPDATE workspace_invitation SET use_count = use_count + 1 WHERE id = #{id} AND tenant_id = #{tenantId} AND revoked = 0 AND expires_at > #{now} AND use_count < max_uses")
    int consume(@Param("id") int id, @Param("tenantId") int tenantId, @Param("now") long now);

    @Update("UPDATE workspace_invitation SET revoked = 1 WHERE id = #{id} AND tenant_id = #{tenantId} AND revoked = 0")
    int revoke(@Param("id") int id, @Param("tenantId") int tenantId);

    // A database write lock serializes joining against changes to the workspace's enabled state.
    @Update("UPDATE tenant SET enable = enable WHERE id = #{tenantId} AND enable = 1")
    int lockEnabledTenant(@Param("tenantId") int tenantId);

    @Update("UPDATE `user` SET enable = enable WHERE id = #{issuerId} AND tenant_id = #{tenantId} AND role = 'TENANT_ADMIN' AND enable = 1")
    int lockEnabledIssuer(@Param("issuerId") int issuerId, @Param("tenantId") int tenantId);
}
