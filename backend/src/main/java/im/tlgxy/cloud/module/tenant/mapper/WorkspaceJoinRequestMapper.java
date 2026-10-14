package im.tlgxy.cloud.module.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkspaceJoinRequestMapper extends BaseMapper<WorkspaceJoinRequest> {
    @Update("UPDATE workspace_join_request SET status = #{status}, reviewed_at = #{now}, reviewer_user_id = #{reviewerId} WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'PENDING'")
    int reviewPending(@Param("id") int id, @Param("tenantId") int tenantId,
                      @Param("status") String status, @Param("now") long now, @Param("reviewerId") int reviewerId);
}
