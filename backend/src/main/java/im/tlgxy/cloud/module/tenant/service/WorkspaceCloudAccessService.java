package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Explicit cloud admission. Public member lists contain no account, mail, credential or peer data. */
@Service
@RequiredArgsConstructor
public class WorkspaceCloudAccessService {
    private final TenantSecurityService security;
    private final TenantMapper tenants;
    private final StorageSourceMapper sources;
    private final UserStorageSourceMapper grants;
    private final WorkspaceMemberPermissionService permissions;
    private final JdbcTemplate jdbc;
    public record Cloud(int id, String name, String type, boolean granted, Long quotaBytes, String status) { }
    public record Application(String id, int userId, String displayName, int storageId, String storageName,
                              String reason, String status, String note, long createdAt) { }
    public record Review(boolean approve, Long quotaBytes, Set<String> permissions, String note) { }

    public List<Cloud> available() {
        User actor = member();
        return sources.selectList(new LambdaQueryWrapper<StorageSource>().eq(StorageSource::getTenantId, actor.getTenantId())
                .eq(StorageSource::getEnable, true).orderByAsc(StorageSource::getOrderNum).last("LIMIT 100"))
                .stream().map(source -> {
                    var grant = grants.getByUserIdAndStorageId(actor.getId(), source.getId());
                    boolean enabled = grant != null && Boolean.TRUE.equals(grant.getEnable()) && MemberStoragePaths.personal(grant.getRootPath(), actor.getId());
                    List<String> status = jdbc.queryForList("SELECT status FROM workspace_cloud_request WHERE user_id=? AND storage_id=?",
                            String.class, actor.getId(), source.getId());
                    return new Cloud(source.getId(), source.getName(), source.getType().name(), enabled,
                            enabled ? grant.getQuotaBytes() : null, enabled ? "APPROVED" : status.isEmpty() ? "NONE" : status.getFirst());
                }).toList();
    }

    @Transactional
    public String apply(int storage, String reason) {
        User actor = member();
        if (reason != null && reason.length() > 500) throw new BizException("申请说明不能超过500字");
        StorageSource source = sources.selectById(storage);
        if (source == null || !Boolean.TRUE.equals(source.getEnable()) || !Objects.equals(source.getTenantId(), actor.getTenantId()))
            throw new BizException("存储源不可申请");
        String id = UUID.randomUUID().toString().replace("-", ""); long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO workspace_cloud_request(id,user_id,tenant_id,storage_id,reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    id, actor.getId(), actor.getTenantId(), storage, reason == null ? "" : reason.trim(), now, now);
        } catch (DataAccessException duplicate) {
            var rows = jdbc.queryForList("SELECT id,status FROM workspace_cloud_request WHERE user_id=? AND storage_id=?", actor.getId(), storage);
            if (rows.isEmpty()) throw duplicate;
            var row = rows.getFirst(); id = (String) row.get("id");
            if ("PENDING".equals(row.get("status"))) return id;
            jdbc.update("UPDATE workspace_cloud_request SET status='PENDING',reason=?,review_note='',reviewed_by=NULL,updated_at=? WHERE id=?",
                    reason == null ? "" : reason.trim(), now, id);
        }
        return id;
    }

    public List<Application> pending() {
        int tenant = manager();
        return jdbc.query("SELECT r.*,u.nickname,s.name AS storage_name FROM workspace_cloud_request r JOIN `user` u ON u.id=r.user_id "
                        + "JOIN storage_source s ON s.id=r.storage_id WHERE r.tenant_id=? AND r.status='PENDING' ORDER BY r.created_at LIMIT 100",
                (row, index) -> new Application(row.getString("id"), row.getInt("user_id"), row.getString("nickname"), row.getInt("storage_id"),
                        row.getString("storage_name"), row.getString("reason"), row.getString("status"), row.getString("review_note"), row.getLong("created_at")), tenant);
    }

    @Transactional
    public void review(String id, Review request) {
        int tenant = manager(); User actor = security.currentUser();
        if (request == null || request.note() != null && request.note().length() > 500) throw new BizException("审核参数不正确");
        var rows = jdbc.queryForList("SELECT user_id,storage_id,status FROM workspace_cloud_request WHERE id=? AND tenant_id=?", id, tenant);
        if (rows.isEmpty() || !"PENDING".equals(rows.getFirst().get("status"))) throw new BizException("申请不存在或已审核");
        var row = rows.getFirst();
        int member = ((Number) row.get("user_id")).intValue(), storage = ((Number) row.get("storage_id")).intValue();
        if (jdbc.update("UPDATE workspace_cloud_request SET status=?,review_note=?,reviewed_by=?,updated_at=? WHERE id=? AND tenant_id=? AND status='PENDING'",
                request.approve() ? "APPROVED" : "REJECTED", request.note() == null ? "" : request.note().trim(), actor.getId(), System.currentTimeMillis(), id, tenant) != 1)
            throw new BizException("申请已由其他管理员处理，请刷新");
        if (request.approve()) {
            var view = permissions.get(member);
            var source = view.storages().stream().filter(s -> s.storageSourceId() == storage).findFirst()
                    .orElseThrow(() -> new BizException("你当前不能授权此存储源"));
            Set<String> preset = new HashSet<>(view.defaultPermissions()); preset.retainAll(source.allowedPermissions());
            if (preset.isEmpty()) { preset.addAll(Set.of("preview", "download")); preset.retainAll(source.allowedPermissions()); }
            Set<String> operations = request.permissions() == null ? Set.copyOf(preset) : request.permissions();
            Set<String> defaults = new HashSet<>(view.defaultPermissions()); defaults.retainAll(view.allowedDefaultPermissions());
            permissions.save(member, new WorkspaceMemberPermissionService.UpdateRequest(defaults,
                    List.of(new WorkspaceMemberPermissionService.StorageUpdate(storage, source.allowedRootPath(), true, operations,
                            request.quotaBytes() == null ? MemberStorageQuotaService.DEFAULT_QUOTA : request.quotaBytes()))));
        }
    }

    private User member() {
        User actor = security.currentUser();
        if (actor.getRole() != UserRoleEnum.USER || actor.getId() <= 2 || actor.getTenantId() == null) throw new BizException("仅本空间普通成员可以申请云盘");
        active(actor.getTenantId()); return actor;
    }
    private int manager() { int tenant = security.currentManagedTenantId(); active(tenant); return tenant; }
    private void active(int tenant) {
        var row = tenants.selectById(tenant);
        if (row == null || !Boolean.TRUE.equals(row.getEnable())) throw new BizException("空间已停用");
    }
}
