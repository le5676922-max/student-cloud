package io.studentcloud.drive.application;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.LocalFileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent workspace approvals. Global local-storage approval is separate from daily member grants. */
public final class WorkspaceCloudService {
    private static final Set<String> PERMISSIONS = Set.of("list", "read", "mkdir", "upload", "copy", "move", "rename", "delete");
    private static final String CLOUD_COLUMNS = "id,requester_id,mount_id,workspace_id,status,decided_by,reason,created_at,decided_at";
    private static final String LOCAL_COLUMNS = "id,requester_id,workspace_id,name,status,mount_id,decided_by,reason,created_at,decided_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Path managedRoot;
    private final MountService mounts;
    private final AccountDirectory accounts;

    public WorkspaceCloudService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, Path managedRoot, AccountDirectory accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(30);
        this.clock = Objects.requireNonNull(clock);
        this.accounts = Objects.requireNonNull(accounts);
        this.managedRoot = managedRoot == null ? null : managedRoot.toAbsolutePath().normalize();
        this.mounts = new MountService(jdbc, manager, clock, managedRoot, accounts);
    }

    public CloudAccessRequestView requestCloud(Actor supplied, long mountId) {
        return requestCloud(supplied,mountId,null);
    }
    public CloudAccessRequestView requestCloud(Actor supplied, long mountId,String explanation) {
        String purpose=explanation==null || explanation.isBlank()?null:reason(explanation);
        return safe(() -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.USER);
            MountRow mount = mount(mountId, false);
            requireWorkspace(actor, mount.workspaceId());
            List<Boolean> grants = jdbc.queryForList("SELECT enabled FROM sc_drive_grant WHERE user_id=? AND mount_id=?", Boolean.class, actor.id(), mountId);
            if (grants.size() == 1 && Boolean.TRUE.equals(grants.getFirst())) throw conflict("此云盘已开通，无需重复申请");
            String pending = actor.id() + ":" + mountId;
            List<CloudAccessRequestView> existing = jdbc.query("SELECT " + CLOUD_COLUMNS + " FROM sc_cloud_access_request WHERE pending_key=?", this::cloud, pending);
            if (!existing.isEmpty()) return existing.getFirst();
            long id;
            try { id = insertCloud(actor, mountId, pending,purpose); }
            catch (DuplicateKeyException raced) {
                List<CloudAccessRequestView> winner = jdbc.query("SELECT " + CLOUD_COLUMNS + " FROM sc_cloud_access_request WHERE pending_key=?", this::cloud, pending);
                if (winner.size() == 1) return winner.getFirst();
                throw conflict("申请状态已变化，请刷新后查看");
            }
            return cloudById(id, false);
        }));
    }

    public List<CloudAccessRequestView> listMyRequests(Actor supplied) {
        return safe(() -> {
            Actor actor = currentActor(supplied);
            return jdbc.query("SELECT " + CLOUD_COLUMNS + " FROM sc_cloud_access_request WHERE requester_id=? ORDER BY id DESC LIMIT 200", this::cloud, actor.id());
        });
    }

    public List<CloudAccessRequestView> pendingForWorkspaceAdmin(Actor supplied) {
        return safe(() -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            return jdbc.query("SELECT " + CLOUD_COLUMNS + " FROM sc_cloud_access_request WHERE workspace_id=? AND status='PENDING' ORDER BY id LIMIT 200", this::cloud, actor.workspaceId());
        });
    }

    public CloudAccessRequestView approveCloud(Actor supplied, long requestId, Long quota, Set<String> permissions) {
        List<Path> created = new ArrayList<>();
        return withCompensation(created, () -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            CloudAccessRequestView request = cloudById(requestId, true);
            requireWorkspace(actor, request.workspaceId());
            if (!request.status().equals("PENDING")) throw conflict("此申请已处理，请刷新后查看");
            Actor member = user(request.requesterId());
            if (member.role() != Role.USER) throw denied();
            requireWorkspace(member, actor.workspaceId());
            MountRow mount = mount(request.mountId(), true);
            requireWorkspace(actor, mount.workspaceId());
            Set<String> selected = validatePermissions(permissions, false);
            validateQuota(quota);
            requireDelegation(actor, mount, selected);
            ensurePersonalDirectory(mount, member, created);
            requireUnchanged(actor, currentActor(supplied));
            saveGrant(member, mount.id(), quota, selected);
            if (jdbc.update("UPDATE sc_cloud_access_request SET status='APPROVED',pending_key=NULL,decided_by=?,reason=NULL,decided_at=? WHERE id=? AND status='PENDING'", actor.id(), clock.millis(), requestId) != 1)
                throw conflict("申请状态已变化，请刷新后查看");
            return cloudById(requestId, false);
        }));
    }

    public CloudAccessRequestView rejectCloud(Actor supplied, long requestId, String reason) {
        return safe(() -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            CloudAccessRequestView request = cloudById(requestId, true);
            requireWorkspace(actor, request.workspaceId());
            if (!request.status().equals("PENDING")) throw conflict("此申请已处理，请刷新后查看");
            if (jdbc.update("UPDATE sc_cloud_access_request SET status='REJECTED',pending_key=NULL,decided_by=?,reason=?,decided_at=? WHERE id=? AND status='PENDING'",
                    actor.id(), reason(reason), clock.millis(), requestId) != 1) throw conflict("申请状态已变化");
            return cloudById(requestId, false);
        }));
    }

    /** Only the current workspace administrator may change a USER's fixed personal-root grant. */
    public void updateMemberGrant(Actor supplied, long userId, long mountId, Long quota, Set<String> permissions) {
        List<Path> created = new ArrayList<>();
        withCompensation(created, () -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            Actor member = user(userId);
            if (member.role() != Role.USER) throw denied();
            requireWorkspace(member, actor.workspaceId());
            MountRow mount = mount(mountId, true);
            requireWorkspace(actor, mount.workspaceId());
            Set<String> selected = validatePermissions(permissions, true);
            validateQuota(quota);
            requireDelegation(actor, mount, selected);
            List<String> current = jdbc.queryForList("SELECT root_path FROM sc_drive_grant WHERE user_id=? AND mount_id=? FOR UPDATE", String.class, userId, mountId);
            if (current.size() != 1 || !DrivePath.of(current.getFirst()).value().equals(personalRoot(member)))
                throw denied();
            if (!selected.isEmpty()) ensurePersonalDirectory(mount, member, created);
            requireUnchanged(actor, currentActor(supplied));
            saveGrant(member, mountId, quota, selected);
            return null;
        }));
    }

    public LocalStorageRequestView requestLocal(Actor supplied, String name) {
        return safe(() -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            String label = name(name);
            String pending = Long.toString(actor.id());
            List<LocalStorageRequestView> existing = jdbc.query("SELECT " + LOCAL_COLUMNS + " FROM sc_local_storage_request WHERE pending_key=?", this::local, pending);
            if (!existing.isEmpty()) return existing.getFirst();
            GeneratedKeyHolder generated = new GeneratedKeyHolder();
            try {
                jdbc.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement("INSERT INTO sc_local_storage_request(requester_id,workspace_id,name,status,pending_key,created_at) VALUES(?,?,?,'PENDING',?,?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, actor.id()); statement.setLong(2, actor.workspaceId());
                    statement.setString(3, label); statement.setString(4, pending); statement.setLong(5, clock.millis());
                    return statement;
                }, generated);
            } catch (DuplicateKeyException raced) {
                List<LocalStorageRequestView> winner = jdbc.query("SELECT " + LOCAL_COLUMNS + " FROM sc_local_storage_request WHERE pending_key=?", this::local, pending);
                if (winner.size() == 1) return winner.getFirst();
                throw conflict("申请状态已变化，请刷新后查看");
            }
            return localById(Objects.requireNonNull(generated.getKey()).longValue(), false);
        }));
    }

    public List<LocalStorageRequestView> listMyLocalRequests(Actor supplied) {
        return safe(() -> {
            Actor actor = requireRole(currentActor(supplied), Role.TENANT_ADMIN);
            return jdbc.query("SELECT " + LOCAL_COLUMNS + " FROM sc_local_storage_request WHERE requester_id=? ORDER BY id DESC LIMIT 200", this::local, actor.id());
        });
    }

    public List<LocalStorageRequestView> pendingLocalForGlobalAdmin(Actor supplied) {
        return safe(() -> {
            requireRole(currentActor(supplied), Role.ADMIN);
            return jdbc.query("SELECT " + LOCAL_COLUMNS + " FROM sc_local_storage_request WHERE status='PENDING' ORDER BY id LIMIT 200", this::local);
        });
    }

    public LocalStorageRequestView approveLocal(Actor supplied, long requestId) {
        List<Path> created = new ArrayList<>();
        return withCompensation(created, () -> transactions.execute(tx -> {
            Actor admin = requireRole(currentActor(supplied), Role.ADMIN);
            LocalStorageRequestView request = localById(requestId, true);
            if (!request.status().equals("PENDING")) throw conflict("此本地存储申请已处理，请刷新后查看");
            Actor tenant = requireRole(user(request.requesterId()), Role.TENANT_ADMIN);
            requireWorkspace(tenant, request.workspaceId());
            Path allowed = configuredRoot();
            LocalFileStore managed = kernel(allowed);
            DrivePath spacePath = DrivePath.of("/space-" + request.workspaceId());
            createOwnedDirectory(managed, spacePath, allowed, created);
            DrivePath drivePath = spacePath.child("drive-" + request.id() + "-" + UUID.randomUUID().toString().substring(0, 12));
            Path physical = allowed.resolve(drivePath.relative());
            // The random allocation is owned solely by this approval; no caller chooses a host path.
            if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) throw conflict("分配目录发生冲突，请重试");
            createOwnedDirectory(managed, drivePath, allowed, created);
            LocalFileStore store = kernel(physical);
            createOwnedDirectory(store, DrivePath.of("/admins"), physical, created);
            createOwnedDirectory(store, DrivePath.of(personalRoot(tenant)), physical, created);
            requireRole(currentActor(supplied), Role.ADMIN);
            MountView mount = mounts.createLocal(admin, request.name(), "ws" + request.workspaceId() + "-local-" + request.id(), physical, request.workspaceId());
            saveGrant(tenant, mount.id(), null, PERMISSIONS);
            if (jdbc.update("UPDATE sc_local_storage_request SET status='APPROVED',pending_key=NULL,mount_id=?,decided_by=?,reason=NULL,decided_at=? WHERE id=? AND status='PENDING'",
                    mount.id(), admin.id(), clock.millis(), request.id()) != 1) throw conflict("申请状态已变化");
            return localById(request.id(), false);
        }));
    }

    public LocalStorageRequestView rejectLocal(Actor supplied, long requestId, String reason) {
        return safe(() -> transactions.execute(tx -> {
            Actor actor = requireRole(currentActor(supplied), Role.ADMIN);
            LocalStorageRequestView request = localById(requestId, true);
            if (!request.status().equals("PENDING")) throw conflict("此本地存储申请已处理，请刷新后查看");
            if (jdbc.update("UPDATE sc_local_storage_request SET status='REJECTED',pending_key=NULL,decided_by=?,reason=?,decided_at=? WHERE id=? AND status='PENDING'",
                    actor.id(), reason(reason), clock.millis(), request.id()) != 1) throw conflict("申请状态已变化");
            return localById(request.id(), false);
        }));
    }

    private long insertCloud(Actor actor, long mountId, String pending,String purpose) {
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO sc_cloud_access_request(requester_id,mount_id,workspace_id,status,pending_key,created_at,reason) VALUES(?,?,?,'PENDING',?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actor.id()); statement.setLong(2, mountId); statement.setLong(3, actor.workspaceId());
            statement.setString(4, pending); statement.setLong(5, clock.millis());
            statement.setString(6,purpose);
            return statement;
        }, generated);
        return Objects.requireNonNull(generated.getKey()).longValue();
    }

    private void requireDelegation(Actor tenant, MountRow mount, Set<String> selected) {
        List<GrantRow> grants = jdbc.query("SELECT root_path,permissions,enabled FROM sc_drive_grant WHERE user_id=? AND mount_id=? FOR UPDATE",
                (result, index) -> new GrantRow(result.getString("root_path"), permissionCsv(result.getString("permissions")), result.getBoolean("enabled")), tenant.id(), mount.id());
        if (grants.size() != 1 || !grants.getFirst().enabled()
                || !DrivePath.of(grants.getFirst().root()).value().equals(personalRoot(tenant))
                || !grants.getFirst().permissions().containsAll(selected)) throw denied();
    }

    private void saveGrant(Actor user, long mountId, Long quota, Set<String> permissions) {
        String values = permissions.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        int updated = jdbc.update("UPDATE sc_drive_grant SET root_path=?,permissions=?,quota_bytes=?,enabled=?,revision=revision+1 WHERE user_id=? AND mount_id=?",
                personalRoot(user), values, quota, !permissions.isEmpty(), user.id(), mountId);
        if (updated == 0) jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,quota_bytes,enabled,revision,created_at) VALUES(?,?,?,?,?,?,1,?)",
                user.id(), mountId, personalRoot(user), values, quota, !permissions.isEmpty(), clock.millis());
    }

    private void ensurePersonalDirectory(MountRow mount, Actor user, List<Path> created) {
        if(!"LOCAL".equals(mount.engine()))return; // Object roots are virtual prefixes, never host paths.
        Path root = validatedMountRoot(mount.physicalRoot());
        LocalFileStore store = kernel(root);
        createOwnedDirectory(store, DrivePath.of(user.role() == Role.USER ? "/members" : "/admins"), root, created);
        createOwnedDirectory(store, DrivePath.of(personalRoot(user)), root, created);
    }

    private static void createOwnedDirectory(LocalFileStore store, DrivePath path, Path root, List<Path> created) {
        try {
            boolean existed = store.stat(path).isPresent();
            store.createDirectory(path);
            if (!existed) created.add(root.resolve(path.relative()));
        } catch (IOException failure) { throw ioFailure(); }
    }

    private Path configuredRoot() {
        if (managedRoot == null) throw fault("DRIVE_NOT_CONFIGURED", 503, "尚未配置独立后端受控存储根目录");
        kernel(managedRoot);
        try { return managedRoot.toRealPath(); }
        catch (IOException unavailable) { throw ioFailure(); }
    }

    private Path validatedMountRoot(String value) {
        Path allowed = configuredRoot();
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(allowed)) throw denied();
        kernel(path);
        return path;
    }

    private static LocalFileStore kernel(Path root) {
        try { return new LocalFileStore(root, MountService.MAX_ENTRIES, MountService.MAX_TRANSFER_BYTES); }
        catch (IOException unavailable) { throw ioFailure(); }
    }

    private MountRow mount(long id, boolean lock) {
        List<MountRow> values = jdbc.query("SELECT id,workspace_id,physical_root,engine,enabled FROM sc_drive_mount WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (result, index) -> {
                    if (!result.getBoolean("enabled")) throw denied();
                    if (!Set.of("LOCAL","S3","ALIYUN_OSS").contains(result.getString("engine"))) throw fault("DRIVE_ENGINE_UNAVAILABLE", 501, "当前申请流程尚未支持该协议");
                    return new MountRow(result.getLong("id"), result.getObject("workspace_id", Long.class), result.getString("physical_root"),result.getString("engine"));
                }, id);
        if (values.size() != 1) throw denied();
        return values.getFirst();
    }

    private Actor currentActor(Actor supplied) {
        return accounts.current(supplied);
    }

    private Actor user(long id) {
        return accounts.account(id);
    }

    private CloudAccessRequestView cloudById(long id, boolean lock) {
        List<CloudAccessRequestView> values = jdbc.query("SELECT " + CLOUD_COLUMNS + " FROM sc_cloud_access_request WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::cloud, id);
        if (values.size() != 1) throw denied();
        return values.getFirst();
    }

    private LocalStorageRequestView localById(long id, boolean lock) {
        List<LocalStorageRequestView> values = jdbc.query("SELECT " + LOCAL_COLUMNS + " FROM sc_local_storage_request WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::local, id);
        if (values.size() != 1) throw denied();
        return values.getFirst();
    }

    private CloudAccessRequestView cloud(ResultSet result, int index) throws SQLException {
        return new CloudAccessRequestView(result.getLong("id"), result.getLong("requester_id"), result.getLong("mount_id"), result.getLong("workspace_id"),
                result.getString("status"), result.getObject("decided_by", Long.class), result.getString("reason"),
                Instant.ofEpochMilli(result.getLong("created_at")), instant(result.getObject("decided_at", Long.class)));
    }

    private LocalStorageRequestView local(ResultSet result, int index) throws SQLException {
        return new LocalStorageRequestView(result.getLong("id"), result.getLong("requester_id"), result.getLong("workspace_id"), result.getString("name"),
                result.getString("status"), result.getObject("mount_id", Long.class), result.getObject("decided_by", Long.class), result.getString("reason"),
                Instant.ofEpochMilli(result.getLong("created_at")), instant(result.getObject("decided_at", Long.class)));
    }

    private static Instant instant(Long time) { return time == null ? null : Instant.ofEpochMilli(time); }
    private static String personalRoot(Actor actor) { return (actor.role() == Role.USER ? "/members/u" : "/admins/u") + actor.id(); }
    private static Actor requireRole(Actor actor, Role role) { if (actor.role() != role) throw denied(); return actor; }
    private static void requireUnchanged(Actor before, Actor current) {
        if (before.id() != current.id() || before.role() != current.role() || !Objects.equals(before.workspaceId(), current.workspaceId()))
            throw fault("DRIVE_CHANGED", 409, "账号或所属空间已变化，请刷新后重新操作");
    }
    private static void requireWorkspace(Actor actor, Long workspace) {
        if (workspace == null || actor.workspaceId() == null || !actor.workspaceId().equals(workspace)) throw denied();
    }
    private static Set<String> validatePermissions(Set<String> values, boolean allowEmpty) {
        if (values == null || !PERMISSIONS.containsAll(values) || (!allowEmpty && values.isEmpty()))
            throw fault("DRIVE_PERMISSION_INVALID", 400, "请选择已支持的操作权限");
        return Set.copyOf(values);
    }
    private static Set<String> permissionCsv(String values) {
        if (values == null || values.length() > 1024) throw denied();
        if (values.isBlank()) return Set.of();
        Set<String> permissions = new java.util.HashSet<>(Arrays.asList(values.split(",", -1)));
        if (!PERMISSIONS.containsAll(permissions)) throw denied();
        return Set.copyOf(permissions);
    }
    private static void validateQuota(Long quota) {
        if (quota != null && quota < 0) throw fault("DRIVE_QUOTA_INVALID", 400, "容量上限不能为负数");
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 120 || value.chars().anyMatch(Character::isISOControl))
            throw fault("DRIVE_INVALID", 400, "请填写不超过120字的存储名称");
        return value.trim();
    }
    private static String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500 || value.chars().anyMatch(Character::isISOControl))
            throw fault("DRIVE_INVALID", 400, "请填写不超过500字的处理原因");
        return value.trim();
    }

    /** Only empty directories allocated by this operation may be compensated; never recurse into user data. */
    private <T> T withCompensation(List<Path> created, Action<T> action) {
        try { return safe(action); }
        catch (RuntimeException failed) {
            if (managedRoot != null) for (Path path : created.reversed()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(managedRoot) || normalized.equals(managedRoot)) continue;
                try {
                    if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized))
                        Files.delete(normalized);
                } catch (IOException ignored) {
                    // Non-empty or changed directories are preserved; DB transaction stays PENDING.
                }
            }
            throw failed;
        }
    }

    private static <T> T safe(Action<T> action) {
        try { return action.run(); }
        catch (ApplicationFault failure) { throw failure; }
        catch (IllegalArgumentException failure) { throw fault("DRIVE_INVALID", 400, "申请或授权参数无效"); }
        catch (DataAccessException failure) { throw fault("DRIVE_DATABASE_UNAVAILABLE", 503, "申请状态暂时无法确认，请刷新后查看"); }
    }
    private static ApplicationFault denied() { return fault("DRIVE_FORBIDDEN", 403, "当前账号无权处理此申请或授权"); }
    private static ApplicationFault conflict(String message) { return fault("DRIVE_CHANGED", 409, message); }
    private static ApplicationFault ioFailure() { return fault("DRIVE_IO_FAILED", 409, "目录准备未完成，申请仍待处理，请检查受控存储状态后重试"); }
    private static ApplicationFault fault(String code, int status, String message) { return new ApplicationFault(code, status, message); }
    @FunctionalInterface private interface Action<T> { T run(); }
    private record MountRow(long id, Long workspaceId, String physicalRoot,String engine) { }
    private record GrantRow(String root, Set<String> permissions, boolean enabled) { }
}
