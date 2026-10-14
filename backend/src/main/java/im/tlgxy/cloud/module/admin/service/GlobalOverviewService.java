package im.tlgxy.cloud.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import im.tlgxy.cloud.core.cache.overview.GlobalOverviewCache;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.mapper.AgentTaskMapper;
import im.tlgxy.cloud.module.ai.model.automation.AgentTask;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.share.mapper.ShareLinkMapper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.enums.LoginLogModeEnum;
import im.tlgxy.cloud.module.user.aspect.LoginLogAspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** System-wide read model. No tenant administrator can reach this service. */
@Service
public class GlobalOverviewService {
    private final TenantSecurityService security;
    private final UserMapper users;
    private final TenantMapper tenants;
    private final StorageSourceMapper storages;
    private final ShareLinkMapper shares;
    private final AgentTaskMapper tasks;
    private final JdbcTemplate jdbc;
    private final SystemConfigService systemConfig;
    private final GlobalOverviewCache overviewCache;

    @Autowired
    public GlobalOverviewService(TenantSecurityService security, UserMapper users, TenantMapper tenants,
                                 StorageSourceMapper storages, ShareLinkMapper shares, AgentTaskMapper tasks,
                                 JdbcTemplate jdbc, SystemConfigService systemConfig, GlobalOverviewCache overviewCache) {
        this.security = security;
        this.users = users;
        this.tenants = tenants;
        this.storages = storages;
        this.shares = shares;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.systemConfig = systemConfig;
        this.overviewCache = overviewCache;
    }

    public GlobalOverviewService(TenantSecurityService security, UserMapper users, TenantMapper tenants,
                                 StorageSourceMapper storages, ShareLinkMapper shares, AgentTaskMapper tasks,
                                 JdbcTemplate jdbc, SystemConfigService systemConfig) {
        this(security, users, tenants, storages, shares, tasks, jdbc, systemConfig, GlobalOverviewCache.uncached());
    }

    public record Workspace(Integer id, String name, boolean enabled, long members, long storageSources,
                            long workspaceAdmins, long pendingMembers, String ownerAccountId) { }
    public record WorkspacePage(List<Workspace> records, long total, int page, int pageSize) { }
    public record WorkspaceMember(Integer id, String accountId, String username, String nickname, String email,
                                  String role, Integer tenantId, String tenantName, boolean enable,
                                  String membershipStatus, Long createTime) { }
    public record WorkspaceMemberPage(List<WorkspaceMember> records, long total, int page, int pageSize) { }
    public record Statuses(long enabledWorkspaces, long disabledWorkspaces, long activeMembers, long disabledMembers,
                           long pendingMemberships, long enabledStorages, long expiredShares,
                           long runningAgentTasks, long failedAgentTasks, long enabledSchedules, long failedSchedules) { }
    public record LoginSummary(long successes24h, long failures24h) { }
    public record DailyLogin(String date, long successes, long failures) { }
    public record RecentLogin(Integer id, String username, Long createdAt, boolean success) { }
    public record RuntimeStatus(long uptimeMs, long usedHeapBytes, long maxHeapBytes,
                                String databaseType, long databaseLatencyMs) { }
    public record Overview(long workspaces, long members, long workspaceAdmins, long storageSources,
                           long shares, long pendingAgentTasks, List<Workspace> workspacePreview,
                           long generatedAt, String timeZone, Statuses statuses, LoginSummary loginSummary,
                           List<DailyLogin> dailyLogins, List<RecentLogin> recentLogins, RuntimeStatus runtime,
                           boolean loginLoggingEnabled) { }

    // All projections are explicit. Never load user credentials, invitation tokens, or private Agent content.
    private static final String WORKSPACE_SELECT = """
            SELECT t.id, t.name, t.enable, COALESCE(u.member_count, 0) AS member_count,
                   COALESCE(s.storage_count, 0) AS storage_count,
                   COALESCE(u.admin_count, 0) AS admin_count,
                   COALESCE(u.pending_count, 0) AS pending_count, owner.account_id AS owner_account_id
            FROM tenant t
            LEFT JOIN (SELECT member.tenant_id, COUNT(*) AS member_count,
                        SUM(CASE WHEN member.role = 'TENANT_ADMIN' THEN 1 ELSE 0 END) AS admin_count,
                        SUM(CASE WHEN request.status = 'PENDING' THEN 1 ELSE 0 END) AS pending_count
                       FROM `user` member LEFT JOIN workspace_join_request request
                         ON request.user_id = member.id AND request.tenant_id = member.tenant_id
                       WHERE member.role IN ('USER', 'TENANT_ADMIN') AND member.id > 2
                       GROUP BY member.tenant_id) u ON u.tenant_id = t.id
            LEFT JOIN (SELECT tenant_id, COUNT(*) AS storage_count FROM storage_source
                       GROUP BY tenant_id) s ON s.tenant_id = t.id
            LEFT JOIN `user` owner ON owner.id = t.owner_user_id
            """;
    private static final RowMapper<Workspace> WORKSPACE_MAPPER = (row, ignored) -> new Workspace(
            row.getInt("id"), row.getString("name"), row.getBoolean("enable"),
            row.getLong("member_count"), row.getLong("storage_count"), row.getLong("admin_count"),
            row.getLong("pending_count"), row.getString("owner_account_id"));

    public Overview overview() {
        // Always fresh: a cached dashboard must never authorize a demoted or disabled administrator.
        security.requireSystemAdmin();
        RuntimeStatus runtime = runtime();
        var statistics = overviewCache.statistics(() -> loadStatistics(runtime.databaseType()));
        var details = overviewCache.details(() -> new GlobalOverviewCache.Details(
                jdbc.query(WORKSPACE_SELECT + " ORDER BY t.id LIMIT 20", WORKSPACE_MAPPER), recentLogins()));
        return new Overview(statistics.workspaces(), statistics.members(), statistics.workspaceAdmins(),
                statistics.storageSources(), statistics.shares(), statistics.pendingAgentTasks(), details.preview(),
                statistics.generatedAt(), statistics.timeZone(), statistics.statuses(), statistics.loginSummary(),
                statistics.dailyLogins(), details.recentLogins(), runtime,
                systemConfig.getSystemConfig().getLoginLogMode() != LoginLogModeEnum.OFF);
    }

    private GlobalOverviewCache.Statistics loadStatistics(String databaseType) {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        String loginTime = timestampExpression("create_time", databaseType);
        return new GlobalOverviewCache.Statistics(
                tenants.selectCount(null),
                users.selectCount(new LambdaQueryWrapper<User>().gt(User::getId, 2)
                        .in(User::getRole, UserRoleEnum.USER, UserRoleEnum.TENANT_ADMIN)),
                users.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, UserRoleEnum.TENANT_ADMIN)),
                storages.selectCount(null), shares.selectCount(null),
                tasks.selectCount(new LambdaQueryWrapper<AgentTask>().eq(AgentTask::getStatus, "PENDING")
                        .gt(AgentTask::getExpiresAt, now)),
                now, zone.getId(), statuses(now, databaseType),
                loginSummary(now, loginTime, databaseType), dailyLogins(now, zone, loginTime, databaseType));
    }

    public WorkspacePage workspaces(String keyword, Boolean enabled, int page, int pageSize) {
        return workspaces(keyword, enabled, page, pageSize, null);
    }

    public WorkspacePage workspaces(String keyword, Boolean enabled, int page, int pageSize, Integer workspaceId) {
        security.requireSystemAdmin();
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100
                || (workspaceId != null && workspaceId < 1)
                || (keyword != null && keyword.length() > 100)) throw new BizException("工作空间查询参数不正确");
        String search = keyword == null ? "" : keyword.trim();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (workspaceId != null) {
            where.append(" AND t.id = ?");
            parameters.add(workspaceId);
        }
        if (!search.isEmpty()) {
            // ! is a portable LIKE escape character; % and _ in a name are literal, not wildcards.
            String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (t.name LIKE ? ESCAPE '!' OR CAST(t.id AS CHAR) LIKE ? ESCAPE '!')");
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (enabled != null) {
            where.append(" AND t.enable = ?");
            parameters.add(enabled);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant t" + where, Long.class, parameters.toArray());
        parameters.add(pageSize);
        parameters.add((long) (page - 1) * pageSize);
        List<Workspace> rows = jdbc.query(WORKSPACE_SELECT + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                WORKSPACE_MAPPER, parameters.toArray());
        return new WorkspacePage(rows, count == null ? 0 : count, page, pageSize);
    }

    /** Read-only global governance projection. Daily member management remains workspace-admin-only. */
    public WorkspaceMemberPage workspaceMembers(int tenantId, String role, String keyword, int page, int pageSize) {
        security.requireSystemAdmin();
        if (tenantId < 1 || page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100
                || (keyword != null && keyword.length() > 100)
                || (role != null && !List.of("TENANT_ADMIN", "USER").contains(role))) {
            throw new BizException("空间成员查询参数不正确");
        }
        Long workspaceExists = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE id = ?", Long.class, tenantId);
        if (workspaceExists == null || workspaceExists == 0) throw new BizException("工作空间不存在");
        String from = """
                FROM `user` u JOIN tenant t ON t.id = u.tenant_id
                LEFT JOIN workspace_join_request r ON r.user_id = u.id AND r.tenant_id = u.tenant_id
                """;
        StringBuilder where = new StringBuilder(" WHERE u.tenant_id = ? AND u.id > 2 AND u.role IN ('TENANT_ADMIN', 'USER')");
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (role != null) {
            where.append(" AND u.role = ?");
            parameters.add(role);
        }
        String search = keyword == null ? "" : keyword.trim();
        if (!search.isEmpty()) {
            String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (u.account_id LIKE ? ESCAPE '!' OR u.username LIKE ? ESCAPE '!'"
                    + " OR u.nickname LIKE ? ESCAPE '!' OR u.email LIKE ? ESCAPE '!')");
            for (int index = 0; index < 4; index++) parameters.add(pattern);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + from + where, Long.class, parameters.toArray());
        parameters.add(pageSize);
        parameters.add((long) (page - 1) * pageSize);
        List<WorkspaceMember> records = jdbc.query("""
                SELECT u.id, u.account_id, u.username, u.nickname, u.email, u.role, u.tenant_id,
                       t.name AS tenant_name, u.enable, u.create_time,
                       CASE WHEN r.id IS NULL OR r.status = 'APPROVED' THEN 'ACTIVE'
                            WHEN r.status = 'PENDING' THEN 'PENDING'
                            WHEN r.status = 'REJECTED' THEN 'REJECTED' ELSE 'UNKNOWN' END AS membership_status
                """ + from + where + " ORDER BY CASE WHEN u.role = 'TENANT_ADMIN' THEN 0 ELSE 1 END, u.id DESC LIMIT ? OFFSET ?",
                (row, ignored) -> {
                    Timestamp created = row.getTimestamp("create_time");
                    return new WorkspaceMember(row.getInt("id"), row.getString("account_id"), row.getString("username"),
                            row.getString("nickname"), row.getString("email"), row.getString("role"), row.getInt("tenant_id"),
                            row.getString("tenant_name"), row.getBoolean("enable"), row.getString("membership_status"),
                            created == null ? null : created.getTime());
                }, parameters.toArray());
        return new WorkspaceMemberPage(records, count == null ? 0 : count, page, pageSize);
    }

    private Statuses statuses(long now, String databaseType) {
        String expiry = timestampExpression("expire_date", databaseType);
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM tenant WHERE enable = TRUE) AS enabled_workspaces,
                  (SELECT COUNT(*) FROM tenant WHERE enable = FALSE) AS disabled_workspaces,
                  (SELECT COUNT(*) FROM `user` u LEFT JOIN workspace_join_request r
                     ON r.user_id = u.id AND r.tenant_id = u.tenant_id
                   WHERE u.id > 2 AND u.role IN ('USER', 'TENANT_ADMIN') AND u.enable = TRUE
                     AND (r.id IS NULL OR r.status = 'APPROVED')) AS active_members,
                  (SELECT COUNT(*) FROM `user` u LEFT JOIN workspace_join_request r
                     ON r.user_id = u.id AND r.tenant_id = u.tenant_id
                   WHERE u.id > 2 AND u.role IN ('USER', 'TENANT_ADMIN') AND (u.enable = FALSE OR u.enable IS NULL)
                     AND (r.id IS NULL OR r.status = 'APPROVED')) AS disabled_members,
                  (SELECT COUNT(*) FROM workspace_join_request r JOIN `user` u
                     ON r.user_id = u.id AND r.tenant_id = u.tenant_id
                   WHERE r.status = 'PENDING' AND u.id > 2 AND u.role = 'USER') AS pending_memberships,
                  (SELECT COUNT(*) FROM storage_source WHERE enable = TRUE) AS enabled_storages,
                  (SELECT COUNT(*) FROM share_link WHERE expire_date IS NOT NULL AND %s <= ?) AS expired_shares,
                  (SELECT COUNT(*) FROM ai_task WHERE status = 'RUNNING') AS running_tasks,
                  (SELECT COUNT(*) FROM ai_task WHERE status IN ('FAILED', 'PARTIAL', 'INTERRUPTED')) AS failed_tasks,
                  (SELECT COUNT(*) FROM ai_schedule WHERE enabled = TRUE) AS enabled_schedules,
                  (SELECT COUNT(*) FROM ai_schedule WHERE last_error IS NOT NULL AND TRIM(last_error) <> '') AS failed_schedules
                """.formatted(expiry), (row, ignored) -> new Statuses(
                row.getLong("enabled_workspaces"), row.getLong("disabled_workspaces"),
                row.getLong("active_members"), row.getLong("disabled_members"), row.getLong("pending_memberships"),
                row.getLong("enabled_storages"), row.getLong("expired_shares"), row.getLong("running_tasks"),
                row.getLong("failed_tasks"), row.getLong("enabled_schedules"), row.getLong("failed_schedules")),
                timestampParameter(now, databaseType));
    }

    private LoginSummary loginSummary(long now, String loginTime, String databaseType) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN result = ? THEN 1 ELSE 0 END), 0) AS successes,
                       COALESCE(SUM(CASE WHEN result = ? THEN 0 ELSE 1 END), 0) AS failures
                FROM login_log WHERE %s >= ? AND %s <= ?
                """.formatted(loginTime, loginTime), (row, ignored) ->
                new LoginSummary(row.getLong("successes"), row.getLong("failures")),
                LoginLogAspect.DEFAULT_LOGIN_SUCCESS_RESULT, LoginLogAspect.DEFAULT_LOGIN_SUCCESS_RESULT,
                timestampParameter(now - 24 * 60 * 60 * 1000L, databaseType), timestampParameter(now, databaseType));
    }

    private List<DailyLogin> dailyLogins(long now, ZoneId zone, String loginTime, String databaseType) {
        LocalDate first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> parameters = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            if (index > 0) sql.append(", ");
            for (int success = 0; success < 2; success++) {
                if (success > 0) sql.append(", ");
                sql.append("COALESCE(SUM(CASE WHEN ").append(loginTime).append(" >= ? AND ")
                        .append(loginTime).append(" < ? AND ")
                        .append(success == 0 ? "result = ?" : "(result <> ? OR result IS NULL)")
                        .append(" THEN 1 ELSE 0 END), 0) AS ").append(success == 0 ? "s" : "f").append(index);
                parameters.add(timestampParameter(first.plusDays(index).atStartOfDay(zone).toInstant().toEpochMilli(), databaseType));
                parameters.add(timestampParameter(first.plusDays(index + 1).atStartOfDay(zone).toInstant().toEpochMilli(), databaseType));
                parameters.add(LoginLogAspect.DEFAULT_LOGIN_SUCCESS_RESULT);
            }
        }
        sql.append(" FROM login_log WHERE ").append(loginTime).append(" >= ? AND ").append(loginTime).append(" <= ?");
        parameters.add(timestampParameter(first.atStartOfDay(zone).toInstant().toEpochMilli(), databaseType));
        parameters.add(timestampParameter(now, databaseType));
        return jdbc.queryForObject(sql.toString(), (row, ignored) -> {
            List<DailyLogin> days = new ArrayList<>(7);
            for (int index = 0; index < 7; index++) days.add(new DailyLogin(first.plusDays(index).toString(),
                    row.getLong("s" + index), row.getLong("f" + index)));
            return List.copyOf(days);
        }, parameters.toArray());
    }

    private List<RecentLogin> recentLogins() {
        return jdbc.query("""
                SELECT id, username, create_time, CASE WHEN result = ? THEN 1 ELSE 0 END AS success
                FROM login_log ORDER BY create_time DESC, id DESC LIMIT 8
                """, (row, ignored) -> {
            Timestamp created = row.getTimestamp("create_time");
            return new RecentLogin(row.getInt("id"), row.getString("username"),
                    created == null ? null : created.getTime(), row.getBoolean("success"));
        }, LoginLogAspect.DEFAULT_LOGIN_SUCCESS_RESULT);
    }

    private RuntimeStatus runtime() {
        long started = System.nanoTime();
        String type = jdbc.execute((ConnectionCallback<String>) connection -> {
            String product = safeDatabaseProduct(connection.getMetaData().getDatabaseProductName());
            try (var statement = connection.prepareStatement("SELECT 1"); var result = statement.executeQuery()) {
                result.next();
            }
            return product;
        });
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new RuntimeStatus(ManagementFactory.getRuntimeMXBean().getUptime(), heap.getUsed(), heap.getMax(),
                type, Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }

    static String safeDatabaseProduct(String product) {
        if (product == null) return "Other";
        return switch (product.toLowerCase(Locale.ROOT)) {
            case "mysql" -> "MySQL";
            case "sqlite" -> "SQLite";
            case "mariadb" -> "MariaDB";
            case "postgresql" -> "PostgreSQL";
            case "h2" -> "H2";
            default -> "Other";
        };
    }

    private static Object timestampParameter(long epochMillis, String databaseType) {
        return "SQLite".equals(databaseType) ? epochMillis : new Timestamp(epochMillis);
    }

    private static String timestampExpression(String column, String databaseType) {
        // SQLite's JDBC driver stores java.util.Date as epoch milliseconds; old SQL imports can contain UTC text.
        return "SQLite".equals(databaseType)
                ? "(CASE WHEN typeof(" + column + ") IN ('integer', 'real') THEN " + column
                + " ELSE strftime('%s', " + column + ") * 1000 END)" : column;
    }
}
