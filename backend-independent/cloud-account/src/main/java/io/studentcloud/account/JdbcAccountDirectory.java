package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Identity-service adapter. Other services must access the HTTP port, not instantiate this adapter. */
public final class JdbcAccountDirectory implements AccountDirectory,WorkspaceDirectory {
    private final JdbcTemplate jdbc;
    public JdbcAccountDirectory(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Actor account(long accountId) {
        if (accountId < 1) throw unavailableAccount();
        List<Actor> rows = jdbc.query("SELECT a.id,a.account_id,a.username,a.email,a.role,a.workspace_id FROM sc_account a LEFT JOIN sc_workspace w ON w.id=a.workspace_id WHERE a.id=? AND a.enabled=TRUE AND (a.role='ADMIN' OR (w.id IS NOT NULL AND w.enabled=TRUE))",
                (r, index) -> new Actor(r.getLong("id"),r.getString("account_id"),r.getString("username"),r.getString("email"),Role.valueOf(r.getString("role")),r.getObject("workspace_id",Long.class)), accountId);
        if (rows.size() != 1) throw unavailableAccount();
        return rows.getFirst();
    }

    @Override public boolean workspaceEnabled(long workspaceId) {
        return workspaceId > 0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sc_workspace WHERE id=? AND enabled=TRUE", Boolean.class, workspaceId));
    }
    @Override public List<Actor> administrators(long workspaceId){
        if(!workspaceEnabled(workspaceId))throw new ApplicationFault("WORKSPACE_UNAVAILABLE",404,"工作空间不存在或已停用");
        var ids=jdbc.queryForList("SELECT id FROM sc_account WHERE workspace_id=? AND role='TENANT_ADMIN' AND enabled=TRUE ORDER BY id LIMIT 101",Long.class,workspaceId);
        if(ids.size()>100)throw new ApplicationFault("WORKSPACE_BUDGET",409,"空间管理员数量超过当前预算");
        return ids.stream().map(this::account).toList();
    }

    private static ApplicationFault unavailableAccount() { return new ApplicationFault("42000",401,"账号或空间已停用，请重新登录"); }
}
