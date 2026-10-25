package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** A tenant requests capacity, never a server path. Approval provisions an isolated managed directory. */
@Service
@RequiredArgsConstructor
public class LocalStorageApprovalService {
    private final JdbcTemplate jdbc;
    private final TenantSecurityService security;
    private final WorkspaceInvitationMapper locks;
    private final WorkspaceStorageProvisioningService provisioning;
    public record Request(String id, int tenantId, String tenantName, String reason, String status,
                          Integer storageId, String reviewNote, long createdAt, Long reviewedAt) { }
    private static final String SELECT = "SELECT r.*, t.name AS tenant_name FROM storage_local_request r JOIN tenant t ON t.id=r.tenant_id";

    public List<Request> list() {
        var actor = security.requireStorageAdministrator();
        if (actor.getRole() == UserRoleEnum.ADMIN)
            return jdbc.query(SELECT + " ORDER BY CASE WHEN r.status='PENDING' THEN 0 ELSE 1 END, r.created_at DESC LIMIT 200", this::row);
        return jdbc.query(SELECT + " WHERE r.tenant_id=? ORDER BY r.created_at DESC LIMIT 200", this::row, actor.getTenantId());
    }

    @Transactional(rollbackFor = Exception.class)
    public String submit(String reason) {
        var actor = security.requireStorageAdministrator();
        if (actor.getRole() != UserRoleEnum.TENANT_ADMIN) throw new BizException("仅空间管理员可提交本地云盘申请");
        String explanation = text(reason, 500);
        if (explanation.isEmpty()) throw new BizException("请说明申请用途");
        lock(actor.getTenantId());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM storage_local_request WHERE tenant_id=? AND status IN ('PENDING','APPROVED')", Long.class, actor.getTenantId()) > 0)
            throw new BizException("已有待审核或已批准的本地云盘申请，请先查看处理结果");
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO storage_local_request(id,tenant_id,requested_by,reason,status,review_note,created_at) VALUES(?,?,?,?,'PENDING','',?)",
                id, actor.getTenantId(), actor.getId(), explanation, System.currentTimeMillis());
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void review(String id, boolean approve, String note) {
        security.requireSystemAdmin();
        var actor = security.currentUser();
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new BizException("申请不存在");
        List<Integer> tenantIds = jdbc.query("SELECT tenant_id FROM storage_local_request WHERE id=?", (rs, n) -> rs.getInt(1), id);
        if (tenantIds.isEmpty()) throw new BizException("申请不存在");
        lock(tenantIds.getFirst());
        Request request = jdbc.query(SELECT + " WHERE r.id=?", this::row, id).getFirst();
        if (!"PENDING".equals(request.status())) throw new BizException("申请已处理，请刷新结果");
        Integer storageId = approve ? provisioning.provisionExistingWorkspace(request.tenantId()).storageId() : null;
        int updated = jdbc.update("UPDATE storage_local_request SET status=?,storage_id=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=? AND status='PENDING'",
                approve ? "APPROVED" : "REJECTED", storageId, actor.getId(), text(note, 500), System.currentTimeMillis(), id);
        if (updated != 1) throw new BizException("申请状态已变化，请刷新结果");
    }

    private void lock(int tenantId) {
        if (locks.lockEnabledTenant(tenantId) != 1) throw new BizException("工作空间不存在或已停用");
    }
    private static String text(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || result.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*")) throw new BizException("说明文字过长或包含无效字符");
        return result;
    }
    private Request row(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Request(rs.getString("id"), rs.getInt("tenant_id"), rs.getString("tenant_name"), rs.getString("reason"),
                rs.getString("status"), (Integer) rs.getObject("storage_id"), rs.getString("review_note"), rs.getLong("created_at"),
                rs.getObject("reviewed_at") == null ? null : rs.getLong("reviewed_at"));
    }
}
