package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Platform-owned public enrollment and administrator-owned invitations; never a cross-service transaction. */
public final class WorkspaceEnrollmentService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WorkspaceEnrollmentService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(15);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Enrollment(long workspaceId, String workspaceName, boolean pending) { }
    public record CreatedInvitation(String id, String code, Long expiresAt, Integer maxUses, int useCount,
                                    boolean revoked, long createdAt) { }
    public record InvitationView(String id, Long expiresAt, Integer maxUses, int useCount,
                                 boolean revoked, long createdAt) { }
    public record JoinRequestView(String id, String accountId, String email, String nickname, String status,
                                  long createdAt) { }

    /** Called only within the transaction that inserts this inactive account and consumes its email proof. */
    public Enrollment enrollMember(String joinType, String joinValue, long newAccountId) {
        requireTransaction();
        freshAccount(newAccountId, Role.USER);
        if ("INVITE".equals(joinType)) return enrollWithInvitation(invitationCode(joinValue), newAccountId);
        if (!"NAME".equals(joinType)) throw invalid("请选择邀请码或空间名称加入");
        String name = workspaceName(joinValue);
        List<Long> matches = jdbc.queryForList("SELECT id FROM sc_workspace WHERE name=? AND enabled=TRUE ORDER BY id LIMIT 2", Long.class, name);
        if (matches.size() != 1) throw invalid("空间名称不存在或无法唯一确定，请向管理员索取邀请码");
        Workspace workspace = workspace(matches.getFirst(), true);
        if (!workspace.name().equals(name)) throw invalid("空间名称已变化，请重新填写或使用邀请码");
        if (count("SELECT COUNT(*) FROM sc_account WHERE workspace_id=? AND role='TENANT_ADMIN' AND enabled=TRUE", workspace.id()) < 1)
            throw invalid("此空间暂不能接收申请，请联系管理员");
        if (count("SELECT COUNT(*) FROM sc_workspace_join_request WHERE workspace_id=? AND status='PENDING'", workspace.id()) >= 200)
            throw conflict("此空间待审批申请较多，请联系管理员使用邀请码加入");
        assignMember(newAccountId, workspace.id(), false);
        jdbc.update("INSERT INTO sc_workspace_join_request(workspace_id,account_id,status,created_at) VALUES(?,?,'PENDING',?)",
                workspace.id(), newAccountId, clock.millis());
        return new Enrollment(workspace.id(), workspace.name(), true);
    }

    /** Ordinary administrator self-registration creates an enabled workspace without an approval step. */
    public Enrollment newWorkspace(long newAccountId, String requestedName) {
        requireTransaction();
        freshAccount(newAccountId, Role.TENANT_ADMIN);
        String name = workspaceName(requestedName);
        jdbc.queryForObject("SELECT id FROM sc_workspace_enrollment_guard WHERE id=1 FOR UPDATE", Long.class);
        if (count("SELECT COUNT(*) FROM sc_workspace WHERE LOWER(name)=?", name.toLowerCase(Locale.ROOT)) != 0)
            throw conflict("此空间名称已被使用，请更换名称");
        long now = clock.millis();
        long workspaceId = insertId("INSERT INTO sc_workspace(name,enabled,created_at) VALUES(?,TRUE,?)", name, now);
        if (jdbc.update("UPDATE sc_account SET workspace_id=?,enabled=TRUE WHERE id=? AND role='TENANT_ADMIN' AND workspace_id IS NULL AND enabled=FALSE",
                workspaceId, newAccountId) != 1) throw changed();
        return new Enrollment(workspaceId, name, false);
    }

    public CreatedInvitation createInvitation(Actor caller, Integer expiresInDays, Integer maxUses) {
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 30)) throw invalid("邀请有效天数须在1至30之间，或留空长期有效");
        if (maxUses != null && (maxUses < 1 || maxUses > 10_000)) throw invalid("邀请人数须在1至10000之间，或留空不限人数");
        return transactions.execute(status -> {
            Current current = administrator(caller);
            workspace(current.workspaceId(), true);
            if (count("SELECT COUNT(*) FROM sc_workspace_invitation WHERE workspace_id=?", current.workspaceId()) >= 200)
                throw conflict("此空间的邀请记录已达到200条，请联系平台维护");
            long now = clock.millis();
            if (count("SELECT COUNT(*) FROM sc_workspace_invitation WHERE workspace_id=? AND created_at>=?", current.workspaceId(), now - 60_000) >= 5)
                throw fault("41005", 429, "请稍后再创建邀请码");
            Long expiresAt = expiresInDays == null ? null : Math.addExact(now, expiresInDays * 86_400_000L);
            byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes);
            String code = HexFormat.of().formatHex(bytes);
            long id = insertId("INSERT INTO sc_workspace_invitation(workspace_id,creator_id,code_hash,expires_at,max_uses,use_count,created_at) VALUES(?,?,?,?,?,0,?)",
                    current.workspaceId(), current.id(), digest(code), expiresAt, maxUses, now);
            return new CreatedInvitation(Long.toString(id), code, expiresAt, maxUses, 0, false, now);
        });
    }

    public List<InvitationView> listInvitations(Actor caller) {
        return transactions.execute(status -> {
            Current current = administrator(caller);
            return jdbc.query("SELECT id,expires_at,max_uses,use_count,revoked_at,created_at FROM sc_workspace_invitation WHERE workspace_id=? ORDER BY id DESC LIMIT 200",
                    (r, index) -> new InvitationView(Long.toString(r.getLong("id")), r.getObject("expires_at", Long.class),
                            r.getObject("max_uses", Integer.class), r.getInt("use_count"), r.getObject("revoked_at", Long.class) != null,
                            r.getLong("created_at")), current.workspaceId());
        });
    }

    public void revokeInvitation(Actor caller, long invitationId) {
        transactions.executeWithoutResult(status -> {
            Current current = administrator(caller);
            workspace(current.workspaceId(), true);
            List<Long> ids = jdbc.queryForList("SELECT id FROM sc_workspace_invitation WHERE id=? AND workspace_id=? FOR UPDATE",
                    Long.class, invitationId, current.workspaceId());
            if (ids.size() != 1) throw missing();
            jdbc.update("UPDATE sc_workspace_invitation SET revoked_at=? WHERE id=? AND revoked_at IS NULL", clock.millis(), invitationId);
        });
    }

    /** Private applicants are visible only to this workspace's enabled ordinary administrator. */
    public List<JoinRequestView> joinRequests(Actor caller) {
        return transactions.execute(status -> {
            Current current = administrator(caller);
            return jdbc.query("SELECT r.id,a.account_id,a.email,a.username,r.status,r.created_at FROM sc_workspace_join_request r JOIN sc_account a ON a.id=r.account_id WHERE r.workspace_id=? AND r.status='PENDING' AND a.workspace_id=r.workspace_id AND a.role='USER' ORDER BY r.id LIMIT 200",
                    (r, index) -> new JoinRequestView(Long.toString(r.getLong("id")), r.getString("account_id"),
                            r.getString("email"), r.getString("username"), r.getString("status"), r.getLong("created_at")), current.workspaceId());
        });
    }

    public void reviewJoinRequest(Actor caller, long requestId, boolean approve) {
        transactions.executeWithoutResult(status -> {
            Current current = administrator(caller);
            workspace(current.workspaceId(), true);
            List<RequestRow> rows = jdbc.query("SELECT id,account_id,status FROM sc_workspace_join_request WHERE id=? AND workspace_id=? FOR UPDATE",
                    (r, index) -> new RequestRow(r.getLong("id"), r.getLong("account_id"), r.getString("status")), requestId, current.workspaceId());
            if (rows.size() != 1) throw missing();
            RequestRow request = rows.getFirst();
            String target = approve ? "APPROVED" : "REJECTED";
            if (!"PENDING".equals(request.status())) {
                if (target.equals(request.status())) return;
                throw conflict("此加入申请已处理，请刷新后核对");
            }
            List<Long> accounts = jdbc.queryForList("SELECT id FROM sc_account WHERE id=? AND workspace_id=? AND role='USER' AND enabled=FALSE FOR UPDATE",
                    Long.class, request.accountId(), current.workspaceId());
            if (accounts.size() != 1) throw changed();
            jdbc.update("UPDATE sc_workspace_join_request SET status=?,reviewed_by=?,reviewed_at=? WHERE id=? AND status='PENDING'",
                    target, current.id(), clock.millis(), request.id());
            if (jdbc.update("UPDATE sc_account SET enabled=?,session_version=session_version+1 WHERE id=? AND workspace_id=? AND role='USER' AND enabled=FALSE",
                    approve, request.accountId(), current.workspaceId()) != 1) throw changed();
            jdbc.update("DELETE FROM sc_session WHERE account_id=?", request.accountId());
        });
    }

    /** The regular enable-account endpoint must call this before enabling a registered member. */
    public void assertMemberActivationAllowed(Actor caller, long memberId) {
        requireTransaction();
        Current current = administrator(caller);
        List<Long> member = jdbc.queryForList("SELECT id FROM sc_account WHERE id=? AND workspace_id=? AND role='USER'", Long.class, memberId, current.workspaceId());
        if (member.size() != 1) throw missing();
        List<String> requests = jdbc.queryForList("SELECT status FROM sc_workspace_join_request WHERE account_id=? AND workspace_id=?", String.class, memberId, current.workspaceId());
        if (!requests.isEmpty() && !"APPROVED".equals(requests.getFirst()))
            throw conflict("此账号尚未通过加入审批，请先在加入申请中处理");
    }

    private Enrollment enrollWithInvitation(String code, long accountId) {
        String hash = digest(code);
        List<Long> targets = jdbc.queryForList("SELECT workspace_id FROM sc_workspace_invitation WHERE code_hash=?", Long.class, hash);
        if (targets.size() != 1) throw invalidInvitation();
        Workspace workspace = workspace(targets.getFirst(), true);
        List<InviteRow> matches = jdbc.query("SELECT id,creator_id,workspace_id,expires_at,max_uses,use_count,revoked_at FROM sc_workspace_invitation WHERE code_hash=? FOR UPDATE",
                WorkspaceEnrollmentService::invitation, hash);
        if (matches.size() != 1) throw invalidInvitation();
        InviteRow invite = matches.getFirst();
        if (invite.workspaceId() != workspace.id() || invite.revokedAt() != null
                || invite.expiresAt() != null && invite.expiresAt() <= clock.millis()
                || invite.maxUses() != null && invite.useCount() >= invite.maxUses()) throw invalidInvitation();
        if (count("SELECT COUNT(*) FROM sc_account WHERE id=? AND workspace_id=? AND role='TENANT_ADMIN' AND enabled=TRUE AND session_version>=1",
                invite.creatorId(), workspace.id()) != 1) throw invalidInvitation();
        if (invite.useCount() == Integer.MAX_VALUE) throw invalidInvitation();
        if (jdbc.update("UPDATE sc_workspace_invitation SET use_count=use_count+1 WHERE id=? AND use_count=? AND revoked_at IS NULL",
                invite.id(), invite.useCount()) != 1) throw changed();
        assignMember(accountId, workspace.id(), true);
        return new Enrollment(workspace.id(), workspace.name(), false);
    }

    private void assignMember(long accountId, long workspaceId, boolean enabled) {
        if (jdbc.update("UPDATE sc_account SET workspace_id=?,enabled=? WHERE id=? AND workspace_id IS NULL AND role='USER' AND enabled=FALSE",
                workspaceId, enabled, accountId) != 1) throw changed();
    }

    private void freshAccount(long accountId, Role role) {
        if (accountId < 1 || count("SELECT COUNT(*) FROM sc_account WHERE id=? AND workspace_id IS NULL AND enabled=FALSE AND role=?", accountId, role.name()) != 1)
            throw changed();
    }

    private Current administrator(Actor caller) {
        if (caller == null || caller.id() < 1) throw unauthorized();
        List<Current> rows = jdbc.query("SELECT id,role,workspace_id,enabled,session_version FROM sc_account WHERE id=? FOR UPDATE",
                (r, index) -> new Current(r.getLong("id"), r.getString("role"), r.getObject("workspace_id", Long.class), r.getBoolean("enabled"), r.getLong("session_version")), caller.id());
        if (rows.size() != 1 || !rows.getFirst().enabled() || rows.getFirst().sessionVersion() < 1) throw unauthorized();
        Current current = rows.getFirst();
        if (!"TENANT_ADMIN".equals(current.role()) || caller.role() != Role.TENANT_ADMIN
                || current.workspaceId() == null || !Objects.equals(current.workspaceId(), caller.workspaceId())) throw forbidden();
        workspace(current.workspaceId(), true);
        return current;
    }

    private Workspace workspace(long id, boolean lock) {
        List<Workspace> rows = jdbc.query("SELECT id,name,enabled FROM sc_workspace WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (r, index) -> new Workspace(r.getLong("id"),r.getString("name"),r.getBoolean("enabled")),id);
        if (rows.size() != 1 || !rows.getFirst().enabled()) throw invalidInvitation();
        return rows.getFirst();
    }

    private long insertId(String sql,Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private long count(String sql,Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Enrollment must share the registration transaction");
    }
    private static String workspaceName(String raw) {
        if(raw==null || raw.length()>160 || raw.chars().anyMatch(Character::isISOControl)) throw invalid("请填写有效的空间名称");
        String name=Normalizer.normalize(raw.trim(),Normalizer.Form.NFC);
        if(name.isBlank() || name.length()>80) throw invalid("空间名称须为1至80字符");
        return name;
    }
    private static String invitationCode(String raw) {
        if(raw==null || !raw.trim().matches("(?i)[a-f0-9]{32}")) throw invalidInvitation();
        return raw.trim().toLowerCase(Locale.ROOT);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException unavailable) {throw new IllegalStateException("Invitation hashing unavailable",unavailable);}
    }
    private static InviteRow invitation(ResultSet r,int index) throws SQLException {
        return new InviteRow(r.getLong("id"),r.getLong("creator_id"),r.getLong("workspace_id"),r.getObject("expires_at",Long.class),r.getObject("max_uses",Integer.class),r.getInt("use_count"),r.getObject("revoked_at",Long.class));
    }
    private static ApplicationFault fault(String code,int status,String message) {return new ApplicationFault(code,status,message);}
    private static ApplicationFault invalid(String message) {return fault("41000",400,message);}
    private static ApplicationFault conflict(String message) {return fault("41000",409,message);}
    private static ApplicationFault changed() {return conflict("账号或申请状态已变化，请刷新后重试");}
    private static ApplicationFault invalidInvitation() {return invalid("邀请码或目标空间不可用，请联系空间管理员");}
    private static ApplicationFault missing() {return fault("40400",404,"邀请或申请不存在，或不属于当前空间");}
    private static ApplicationFault unauthorized() {return fault("42000",401,"请重新登录");}
    private static ApplicationFault forbidden() {return fault("40300",403,"只有本空间普通管理员可以执行此操作");}
    private record Workspace(long id,String name,boolean enabled) { }
    private record Current(long id,String role,Long workspaceId,boolean enabled,long sessionVersion) { }
    private record InviteRow(long id,long creatorId,long workspaceId,Long expiresAt,Integer maxUses,int useCount,Long revokedAt) { }
    private record RequestRow(long id,long accountId,String status) { }
}
