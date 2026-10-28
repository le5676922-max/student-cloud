package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.net.IDN;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authenticated management operations for the independently owned account/workspace model.
 * The caller ID must originate in AccountService.authenticate; this class rechecks its current
 * database role and enabled state for every operation. It is not a public registration service.
 */
public final class AccountAdministration {
    private static final String VIEW_COLUMNS = "a.id,a.account_id,a.username,a.email,a.role,a.workspace_id,a.enabled";
    private static final RowMapper<AccountView> VIEW = (row, index) -> view(row);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PasswordHasher passwords = new PasswordHasher();
    private final java.util.function.BiConsumer<Actor,Long> activationGate;

    public AccountAdministration(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this(jdbc,manager,clock,new WorkspaceEnrollmentService(jdbc,manager,clock)::assertMemberActivationAllowed);
    }

    public AccountAdministration(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            java.util.function.BiConsumer<Actor,Long> activationGate) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(15);
        this.clock = Objects.requireNonNull(clock);
        this.activationGate=Objects.requireNonNull(activationGate);
    }

    /** Creates a dedicated workspace and its ordinary administrator in one database transaction. */
    public AccountView createWorkspaceAdministrator(Actor caller, String username, String email, String password, String workspaceName) {
        String normalizedUsername = username(username);
        String normalizedEmail = email(email);
        String normalizedWorkspace = text(workspaceName, 120, "请填写 1 至 120 字符的空间名称");
        requirePassword(password);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                requireCaller(caller, Role.ADMIN);
                requireAvailableAliases(normalizedUsername, normalizedEmail);
                String encoded = passwords.hash(password);
                long now = clock.millis();
                GeneratedKeyHolder workspace = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO sc_workspace(name,enabled,created_at) VALUES(?,TRUE,?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, normalizedWorkspace);
                    statement.setLong(2, now);
                    return statement;
                }, workspace);
                long workspaceId = Objects.requireNonNull(workspace.getKey(), "Workspace identity was not generated").longValue();
                return insert(normalizedUsername, normalizedEmail, encoded, Role.TENANT_ADMIN, workspaceId, now);
            }));
        } catch (DuplicateKeyException duplicate) {
            throw duplicateAlias();
        }
    }

    /** A workspace administrator can create only USER accounts in their current enabled workspace. */
    public AccountView createMember(Actor caller, String username, String email, String password) {
        return createMember(caller,username,email,password,null);
    }

    public AccountView createMember(Actor caller, String username, String email, String password,String nickname) {
        String normalizedUsername = username(username);
        String normalizedEmail = email==null || email.isBlank()?null:email(email);
        requirePassword(password);
        String displayName=nickname==null || nickname.isBlank()?null:text(nickname,100,"昵称须在100字符以内");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                AccountView current = requireCaller(caller, Role.TENANT_ADMIN);
                requireAvailableAliases(normalizedUsername, normalizedEmail);
                var member=insert(normalizedUsername, normalizedEmail, passwords.hash(password), Role.USER,current.workspaceId(), clock.millis());
                if(displayName!=null) jdbc.update("INSERT INTO sc_public_profile(account_id,nickname,email_verified_at) VALUES(?,?,0)",member.id(),displayName);
                return member;
            }));
        } catch (DuplicateKeyException duplicate) {
            throw duplicateAlias();
        }
    }

    public List<AccountView> listWorkspaceMembers(Actor caller, int offset, int limit, String search) {
        requirePage(offset, limit);
        String filter = search == null ? "" : textOrEmpty(search, 120).toLowerCase(Locale.ROOT);
        String like = "%" + filter.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return Objects.requireNonNull(transactions.execute(status -> {
            AccountView current = requireCaller(caller, Role.TENANT_ADMIN);
            String query = "SELECT " + VIEW_COLUMNS + " FROM sc_account a WHERE a.workspace_id=? AND a.role='USER'";
            if (filter.isEmpty()) return jdbc.query(query + " ORDER BY a.id LIMIT ? OFFSET ?", VIEW, current.workspaceId(), limit, offset);
            return jdbc.query(query + " AND (LOWER(a.username) LIKE ? ESCAPE '!' OR LOWER(a.email) LIKE ? ESCAPE '!' "
                            + "OR LOWER(a.account_id) LIKE ? ESCAPE '!') ORDER BY a.id LIMIT ? OFFSET ?",
                    VIEW, current.workspaceId(), like, like, like, limit, offset);
        }));
    }

    public void setMemberEnabled(Actor caller, long memberId, boolean enabled) {
        transactions.executeWithoutResult(status -> {
            AccountView administrator = requireCaller(caller, Role.TENANT_ADMIN);
            AccountView member = requireMember(administrator.workspaceId(), memberId);
            if(enabled) activationGate.accept(caller,memberId);
            if (member.enabled() != enabled) {
                if (jdbc.update("UPDATE sc_account SET enabled=?,session_version=session_version+1 "
                                + "WHERE id=? AND workspace_id=? AND role='USER'", enabled, memberId, administrator.workspaceId()) != 1)
                    throw changed();
                jdbc.update("DELETE FROM sc_session WHERE account_id=?", memberId);
            } else if (!enabled) {
                // Repeat disable is idempotent and also removes any old or partially revoked sessions.
                jdbc.update("DELETE FROM sc_session WHERE account_id=?", memberId);
            }
        });
    }

    /** Same owner scope and literal search semantics as listWorkspaceMembers. */
    public long countWorkspaceMembers(Actor caller, String search) {
        String filter = search == null ? "" : textOrEmpty(search, 120).toLowerCase(Locale.ROOT);
        String like = "%" + filter.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Long count = transactions.execute(status -> {
            AccountView current = requireCaller(caller, Role.TENANT_ADMIN);
            String query = "SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=? AND a.role='USER'";
            if (filter.isEmpty()) return jdbc.queryForObject(query, Long.class, current.workspaceId());
            return jdbc.queryForObject(query + " AND (LOWER(a.username) LIKE ? ESCAPE '!' OR LOWER(a.email) LIKE ? ESCAPE '!' "
                            + "OR LOWER(a.account_id) LIKE ? ESCAPE '!')", Long.class, current.workspaceId(), like, like, like);
        });
        return Objects.requireNonNull(count);
    }

    /** Reset never reveals the existing password. Every successful reset revokes all old sessions. */
    public void resetMemberPassword(Actor caller, long memberId, String newPassword) {
        requirePassword(newPassword);
        transactions.executeWithoutResult(status -> {
            AccountView administrator = requireCaller(caller, Role.TENANT_ADMIN);
            requireMember(administrator.workspaceId(), memberId);
            String encoded = passwords.hash(newPassword);
            if (jdbc.update("UPDATE sc_account SET password_hash=?,session_version=session_version+1 "
                            + "WHERE id=? AND workspace_id=? AND role='USER'", encoded, memberId, administrator.workspaceId()) != 1)
                throw changed();
            jdbc.update("DELETE FROM sc_session WHERE account_id=?", memberId);
        });
    }

    public List<AccountView> listWorkspaceAdministrators(Actor caller, int offset, int limit) {
        requirePage(offset, limit);
        return Objects.requireNonNull(transactions.execute(status -> {
            requireCaller(caller, Role.ADMIN);
            return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM sc_account a WHERE a.role='TENANT_ADMIN' ORDER BY a.workspace_id,a.id LIMIT ? OFFSET ?",
                    VIEW, limit, offset);
        }));
    }

    public long countWorkspaceAdministrators(Actor caller) {
        Long count = transactions.execute(status -> {
            requireCaller(caller, Role.ADMIN);
            return jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE role='TENANT_ADMIN'", Long.class);
        });
        return Objects.requireNonNull(count);
    }

    private AccountView requireCaller(Actor caller, Role expected) {
        if (caller == null || caller.id() <= 0) throw unauthorized();
        List<CurrentAccount> rows = jdbc.query("SELECT " + VIEW_COLUMNS + ",a.session_version FROM sc_account a WHERE a.id=? FOR UPDATE",
                (row, index) -> new CurrentAccount(view(row), row.getLong("session_version")), caller.id());
        if (rows.size() != 1 || !rows.getFirst().account().enabled() || rows.getFirst().sessionVersion() < 1) throw unauthorized();
        AccountView current = rows.getFirst().account();
        // Reject stale or forged context; the authoritative role and workspace still come from this row.
        if (current.role() != expected || caller.role() != current.role()
                || !Objects.equals(caller.workspaceId(), current.workspaceId())) throw forbidden();
        if (current.role() == Role.ADMIN) {
            if (current.workspaceId() != null) throw unauthorized();
            return current;
        }
        if (current.workspaceId() == null || current.workspaceId() <= 0) throw unauthorized();
        List<Boolean> workspace = jdbc.queryForList("SELECT enabled FROM sc_workspace WHERE id=? FOR UPDATE", Boolean.class, current.workspaceId());
        if (workspace.size() != 1 || !Boolean.TRUE.equals(workspace.getFirst())) throw forbidden();
        return current;
    }

    private AccountView requireMember(long workspaceId, long memberId) {
        if (memberId <= 0) throw missingMember();
        List<AccountView> rows = jdbc.query("SELECT " + VIEW_COLUMNS + " FROM sc_account a "
                        + "WHERE a.id=? AND a.workspace_id=? AND a.role='USER' FOR UPDATE", VIEW, memberId, workspaceId);
        if (rows.size() != 1) throw missingMember();
        return rows.getFirst();
    }

    private AccountView insert(String username, String email, String encodedPassword, Role role, long workspace, long now) {
        String accountId = "SC" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO sc_account(account_id,username,email,password_hash,"
                    + "role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,?,?,?,TRUE,1,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, accountId);
            statement.setString(2, username);
            statement.setString(3, email);
            statement.setString(4, encodedPassword);
            statement.setString(5, role.name());
            statement.setLong(6, workspace);
            statement.setLong(7, now);
            return statement;
        }, key);
        return new AccountView(Objects.requireNonNull(key.getKey(), "Account identity was not generated").longValue(),
                accountId, username, email, role, workspace, true);
    }

    private void requireAvailableAliases(String username, String email) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE LOWER(username)=? OR LOWER(email)=?", Integer.class, username, email);
        if (found != null && found > 0) throw duplicateAlias();
    }

    private static AccountView view(ResultSet row) throws SQLException {
        Role role;
        try { role = Role.valueOf(row.getString("role")); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw unauthorized(); }
        return new AccountView(row.getLong("id"), row.getString("account_id"), row.getString("username"), row.getString("email"),
                role, row.getObject("workspace_id", Long.class), row.getBoolean("enabled"));
    }

    private static String username(String value) {
        String result = text(value, 64, "用户名须为 3 至 64 字符").toLowerCase(Locale.ROOT);
        if (result.length() < 3 || !result.matches("[\\p{L}\\p{N}_.-]+") || result.matches("sc[0-9a-f]+"))
            throw invalid("用户名须使用文字、数字、点、下划线或短横线，不能使用系统账号 ID 格式");
        return result;
    }

    private static String email(String value) {
        String raw = text(value, 254, "请填写有效邮箱地址").toLowerCase(Locale.ROOT);
        int at = raw.indexOf('@');
        if (at < 1 || at != raw.lastIndexOf('@') || at > 64) throw invalid("请填写有效邮箱地址");
        String local = raw.substring(0, at);
        String domain;
        try { domain = IDN.toASCII(raw.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException invalid) { throw invalid("请填写有效邮箱地址"); }
        if (!local.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+") || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || domain.length() > 253 || domain.indexOf('.') < 1 || domain.endsWith(".") || domain.contains(".."))
            throw invalid("请填写有效邮箱地址");
        String result = local + "@" + domain;
        if (result.length() > 254) throw invalid("请填写有效邮箱地址");
        return result;
    }

    private static String text(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.length() > maximum + 20 || value.chars().anyMatch(Character::isISOControl)) throw invalid(message);
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.length() > maximum) throw invalid(message);
        return normalized;
    }

    private static String textOrEmpty(String value, int maximum) {
        if (value.length() > maximum + 20 || value.chars().anyMatch(Character::isISOControl)) throw invalid("搜索内容格式不正确");
        return value.isBlank() ? "" : text(value, maximum, "搜索内容不能超过 120 字符");
    }

    private static void requirePassword(String value) {
        if (!PasswordHasher.validNewPassword(value)) throw invalid("密码须为 8 至 128 字符");
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid("分页参数不正确");
    }

    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private static ApplicationFault unauthorized() { return new ApplicationFault("42000", 401, "请重新登录"); }
    private static ApplicationFault forbidden() { return new ApplicationFault("30000", 403, "当前账号不能执行此管理操作"); }
    private static ApplicationFault missingMember() { return new ApplicationFault("ACCOUNT_NOT_FOUND", 404, "成员不存在或不属于当前空间"); }
    private static ApplicationFault duplicateAlias() { return new ApplicationFault("ACCOUNT_ALREADY_EXISTS", 409, "用户名或邮箱已被使用"); }
    private static ApplicationFault changed() { return new ApplicationFault("ACCOUNT_CHANGED", 409, "账号状态已变化，请刷新后重试"); }
    private record CurrentAccount(AccountView account, long sessionVersion) { }
}
