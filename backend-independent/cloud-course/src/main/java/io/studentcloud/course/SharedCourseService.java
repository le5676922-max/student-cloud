package io.studentcloud.course;

import io.studentcloud.account.Actor;
import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Course-owned sharing independent of workspace/cloud grants. Every mutation locks its course row. */
public final class SharedCourseService {
    private static final long MAX_QUOTA = 1024L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AccountDirectory accounts;

    public SharedCourseService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, AccountDirectory accounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        // A caller waiting for the course lock must read the preceding holder's committed seats.
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public List<CourseView> list(Actor actor) {
        Actor current = actor(actor);
        return jdbc.query("SELECT c.* FROM sc_shared_course c WHERE c.deleted_at IS NULL AND (c.owner_id=? OR EXISTS(SELECT 1 FROM sc_course_member m WHERE m.course_id=c.id AND m.account_id=? AND m.state='ACTIVE')) ORDER BY c.updated_at DESC,c.id DESC LIMIT 300",
                this::course, current.id(), current.id()).stream().map(row -> view(current, row)).toList();
    }

    public CourseView get(Actor actor, long courseId) { return requireRead(actor, courseId); }

    public CourseView create(Actor actor, String name, String description, long quotaBytes, boolean enabled) {
        Actor current = actor(actor);
        if (current.role() != Role.TENANT_ADMIN) throw denied();
        String validName = text(name, 120, false);
        String validDescription = text(description, 2000, true);
        quota(quotaBytes);
        return transactions.execute(status -> {
            // Local guard serializes this service's owner budget without locking another service's database.
            ownerLock(current.id());
            Actor rechecked = actor(current);
            if (rechecked.role() != Role.TENANT_ADMIN || !Objects.equals(rechecked.workspaceId(), current.workspaceId())) throw denied();
            if (count("SELECT COUNT(*) FROM sc_shared_course WHERE owner_id=? AND deleted_at IS NULL", current.id()) >= 50)
                throw conflict("最多创建 50 门课程");
            long now = clock.millis();
            long id = insertId("INSERT INTO sc_shared_course(owner_id,name,description,quota_bytes,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    current.id(), validName, validDescription, quotaBytes, enabled, now, now);
            return view(current, load(id, false));
        });
    }

    public CourseView update(Actor actor, long courseId, String name, String description, long quotaBytes, boolean enabled) {
        String validName = text(name, 120, false);
        String validDescription = text(description, 2000, true);
        quota(quotaBytes);
        return transactions.execute(status -> {
            Actor current = actor(actor);
            CourseRow row = load(courseId, true);
            owner(current, row);
            if (quotaBytes < row.usedBytes()) throw conflict("课程容量不能小于已占用空间");
            jdbc.update("UPDATE sc_shared_course SET name=?,description=?,quota_bytes=?,enabled=?,updated_at=? WHERE id=?",
                    validName, validDescription, quotaBytes, enabled, clock.millis(), courseId);
            return view(current, load(courseId, false));
        });
    }

    /** Material deletion must be coordinated by the material service before deleting a non-empty course. */
    public void delete(Actor actor, long courseId) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            CourseRow row = load(courseId, true);
            owner(current, row);
            if (row.usedBytes() != 0 || row.materialCount() != 0) throw conflict("请先通过资料服务清理课程文件");
            long now = clock.millis();
            jdbc.update("UPDATE sc_shared_course SET enabled=FALSE,deleted_at=?,updated_at=? WHERE id=?", now, now, courseId);
            jdbc.update("UPDATE sc_course_invitation SET revoked_at=? WHERE course_id=? AND revoked_at IS NULL", now, courseId);
            jdbc.update("UPDATE sc_course_member SET state='REMOVED',upload_status='NONE',updated_at=? WHERE course_id=?", now, courseId);
        });
    }

    public CreatedInvitation createInvitation(Actor actor, long courseId, Long expiresAt, Integer maxMembers) {
        return transactions.execute(status -> {
            Actor current = actor(actor);
            CourseRow row = load(courseId, true);
            owner(current, row);
            enabled(row);
            long now = clock.millis();
            if (expiresAt != null && expiresAt <= now) throw invalid("请选择未来的邀请到期时间");
            if (maxMembers != null && (maxMembers < 1 || maxMembers > 500)) throw invalid("邀请人数须在 1 到 500 之间");
            if (count("SELECT COUNT(*) FROM sc_course_invitation WHERE course_id=? AND created_at>=?", courseId, now - 60_000) >= 5)
                throw new ApplicationFault("41000", 429, "请稍后再创建邀请");
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            String code = HexFormat.of().formatHex(bytes);
            long id = insertId("INSERT INTO sc_course_invitation(course_id,code_hash,expires_at,max_members,created_at) VALUES(?,?,?,?,?)",
                    courseId, digest(code), expiresAt, maxMembers, now);
            return new CreatedInvitation(id, code, expiresAt, maxMembers, 0, now);
        });
    }

    public List<InviteView> invitations(Actor actor, long courseId, int offset, int limit) {
        Actor current = actor(actor);
        owner(current, load(courseId, false));
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 50) throw invalid("邀请列表分页无效");
        return jdbc.query("SELECT id,expires_at,max_members,used_count,revoked_at,created_at FROM sc_course_invitation WHERE course_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                (r, index) -> new InviteView(r.getLong("id"), r.getObject("expires_at", Long.class), r.getObject("max_members", Integer.class),
                        r.getLong("used_count"), r.getObject("revoked_at", Long.class), r.getLong("created_at")), courseId, limit, offset);
    }

    public void revokeInvitation(Actor actor, long courseId, long invitationId) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            owner(current, load(courseId, true));
            List<Long> ids = jdbc.queryForList("SELECT id FROM sc_course_invitation WHERE id=? AND course_id=?", Long.class, invitationId, courseId);
            if (ids.isEmpty()) throw missing();
            jdbc.update("UPDATE sc_course_invitation SET revoked_at=? WHERE id=? AND revoked_at IS NULL", clock.millis(), invitationId);
        });
    }

    public InvitationPreview previewInvitation(Actor actor, String code) {
        Actor current = participant(actor);
        InvitationRow invite = invite(code);
        CourseRow course = load(invite.courseId(), false);
        enabled(course);
        usable(invite);
        MemberRow member = member(course.id(), current.id());
        if (member != null && member.state().equals("REMOVED")) throw denied();
        boolean creator = course.ownerId() == current.id();
        boolean joined = member != null && member.state().equals("ACTIVE");
        if (!creator && !joined) availableSeat(invite, current.id());
        return new InvitationPreview(course.id(), course.name(), course.description(), creator, joined, invite.expiresAt());
    }

    public CourseView join(Actor actor, String code) {
        // Lookup obtains only the target ID; validity is re-read while holding the course lock.
        long target = invite(code).courseId();
        return transactions.execute(status -> {
            Actor current = participant(actor);
            CourseRow course = load(target, true);
            enabled(course);
            InvitationRow invitation = invite(code);
            if (invitation.courseId() != course.id()) throw denied();
            usable(invitation);
            MemberRow member = member(course.id(), current.id());
            if (member != null && member.state().equals("REMOVED")) throw denied();
            if (course.ownerId() == current.id() || member != null && member.state().equals("ACTIVE")) return view(current, course);
            availableSeat(invitation, current.id());
            if (count("SELECT COUNT(*) FROM sc_course_member WHERE course_id=? AND state='ACTIVE'", course.id()) >= 500)
                throw conflict("课程加入人数已满");
            // Different course locks alone cannot serialize one account's overall join budget.
            ownerLock(current.id());
            participant(current);
            if (count("SELECT COUNT(*) FROM sc_course_member WHERE account_id=? AND state='ACTIVE'", current.id()) >= 200)
                throw conflict("已加入课程过多，请先退出不再使用的课程");
            long now = clock.millis();
            if (member == null) jdbc.update("INSERT INTO sc_course_member(course_id,account_id,state,upload_status,joined_at,updated_at) VALUES(?,?,'ACTIVE','NONE',?,?)", course.id(), current.id(), now, now);
            else jdbc.update("UPDATE sc_course_member SET state='ACTIVE',upload_status='NONE',joined_at=?,updated_at=? WHERE course_id=? AND account_id=?", now, now, course.id(), current.id());
            if (!used(invitation.id(), current.id())) {
                jdbc.update("INSERT INTO sc_course_invitation_use(invitation_id,account_id,first_used_at) VALUES(?,?,?)", invitation.id(), current.id(), now);
                jdbc.update("UPDATE sc_course_invitation SET used_count=used_count+1 WHERE id=?", invitation.id());
            }
            return view(current, course);
        });
    }

    public void leave(Actor actor, long courseId) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            CourseRow course = load(courseId, true);
            if (course.ownerId() == current.id()) throw conflict("创建者不能退出自己的课程");
            MemberRow member = member(courseId, current.id());
            if (member == null || member.state().equals("REMOVED")) throw denied();
            jdbc.update("UPDATE sc_course_member SET state='LEFT',upload_status='NONE',updated_at=? WHERE course_id=? AND account_id=? AND state='ACTIVE'", clock.millis(), courseId, current.id());
        });
    }

    public List<MemberView> members(Actor actor, long courseId) {
        Actor current = actor(actor);
        owner(current, load(courseId, false));
        return jdbc.query("SELECT account_id,upload_status,joined_at FROM sc_course_member WHERE course_id=? AND state='ACTIVE' ORDER BY joined_at,account_id",
                (r, index) -> new MemberView(r.getLong("account_id"), "课程成员", r.getString("upload_status"), r.getLong("joined_at")), courseId);
    }

    public void requestUpload(Actor actor, long courseId) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            CourseRow course = load(courseId, true);
            enabled(course);
            if (course.ownerId() == current.id()) return;
            MemberRow member = activeMember(courseId, current.id());
            if (!member.uploadStatus().equals("APPROVED"))
                jdbc.update("UPDATE sc_course_member SET upload_status='PENDING',updated_at=? WHERE course_id=? AND account_id=?", clock.millis(), courseId, current.id());
        });
    }

    public void reviewUpload(Actor actor, long courseId, long memberId, boolean approve) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            CourseRow course = load(courseId, true);
            owner(current, course);
            MemberRow member = activeMember(courseId, memberId);
            if (approve && !member.uploadStatus().equals("PENDING") && !member.uploadStatus().equals("APPROVED"))
                throw conflict("成员尚未提交上传申请");
            jdbc.update("UPDATE sc_course_member SET upload_status=?,updated_at=? WHERE course_id=? AND account_id=?",
                    approve ? "APPROVED" : "REJECTED", clock.millis(), courseId, memberId);
        });
    }

    public void removeMember(Actor actor, long courseId, long memberId) {
        transactions.executeWithoutResult(status -> {
            Actor current = actor(actor);
            owner(current, load(courseId, true));
            MemberRow member = member(courseId, memberId);
            if (member == null) throw missing();
            jdbc.update("UPDATE sc_course_member SET state='REMOVED',upload_status='NONE',updated_at=? WHERE course_id=? AND account_id=?", clock.millis(), courseId, memberId);
        });
    }

    public CourseView requireRead(Actor actor, long courseId) {
        Actor current = actor(actor);
        CourseRow row = load(courseId, false);
        if (row.ownerId() != current.id()) {
            enabled(row);
            activeMember(courseId, current.id());
        }
        return view(current, row);
    }

    public CourseView requireUpload(Actor actor, long courseId) {
        Actor current = actor(actor);
        CourseRow row = load(courseId, false);
        enabled(row);
        if (row.ownerId() != current.id() && !activeMember(courseId, current.id()).uploadStatus().equals("APPROVED")) throw denied();
        return view(current, row);
    }

    /** Public Actor fields are transport values; current identity comes from the owning service. */
    private Actor actor(Actor supplied) {
        return accounts.current(supplied);
    }

    private void ownerLock(long accountId) {
        jdbc.update("INSERT INTO sc_content_owner_guard(account_id) VALUES(?) ON DUPLICATE KEY UPDATE account_id=account_id", accountId);
        jdbc.queryForObject("SELECT account_id FROM sc_content_owner_guard WHERE account_id=? FOR UPDATE", Long.class, accountId);
    }

    private Actor participant(Actor actor) {
        Actor current = actor(actor);
        if (current.role() != Role.USER && current.role() != Role.TENANT_ADMIN) throw denied();
        return current;
    }

    private CourseRow load(long id, boolean lock) {
        List<CourseRow> rows = jdbc.query("SELECT * FROM sc_shared_course WHERE id=? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""), this::course, id);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private CourseRow course(ResultSet r, int index) throws SQLException {
        return new CourseRow(r.getLong("id"), r.getLong("owner_id"), r.getString("name"), r.getString("description"), r.getLong("quota_bytes"),
                r.getLong("used_bytes"), r.getLong("material_count"), r.getBoolean("enabled"), r.getLong("created_at"), r.getLong("updated_at"));
    }

    private CourseView view(Actor actor, CourseRow row) {
        boolean creator = row.ownerId() == actor.id();
        MemberRow member = creator ? null : member(row.id(), actor.id());
        String upload = creator ? "APPROVED" : member != null && member.state().equals("ACTIVE") ? member.uploadStatus() : "NONE";
        return new CourseView(row.id(), row.name(), row.description(), row.quotaBytes(), row.usedBytes(), row.materialCount(),
                count("SELECT COUNT(*) FROM sc_course_member WHERE course_id=? AND state='ACTIVE'", row.id()), row.enabled(), creator, upload, row.createdAt(), row.updatedAt());
    }

    private InvitationRow invite(String code) {
        if (code == null || !code.trim().matches("(?i)[0-9a-f]{32}")) throw invalid("邀请码无效或不可用");
        List<InvitationRow> rows = jdbc.query("SELECT id,course_id,expires_at,max_members,used_count,revoked_at FROM sc_course_invitation WHERE code_hash=?",
                (r, index) -> new InvitationRow(r.getLong("id"), r.getLong("course_id"), r.getObject("expires_at", Long.class), r.getObject("max_members", Integer.class),
                        r.getLong("used_count"), r.getObject("revoked_at", Long.class)), digest(code.trim().toLowerCase(Locale.ROOT)));
        if (rows.size() != 1) throw invalid("邀请码无效或不可用");
        return rows.getFirst();
    }

    private void usable(InvitationRow invite) {
        if (invite.revokedAt() != null || invite.expiresAt() != null && invite.expiresAt() <= clock.millis()) throw invalid("邀请码已撤销或过期");
    }

    private void availableSeat(InvitationRow invite, long accountId) {
        if (!used(invite.id(), accountId) && invite.maxMembers() != null && invite.usedCount() >= invite.maxMembers()) throw conflict("此邀请的人数已满");
    }

    private boolean used(long invitationId, long accountId) { return count("SELECT COUNT(*) FROM sc_course_invitation_use WHERE invitation_id=? AND account_id=?", invitationId, accountId) == 1; }

    private MemberRow member(long courseId, long accountId) {
        List<MemberRow> rows = jdbc.query("SELECT state,upload_status FROM sc_course_member WHERE course_id=? AND account_id=?",
                (r, index) -> new MemberRow(r.getString("state"), r.getString("upload_status")), courseId, accountId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private MemberRow activeMember(long courseId, long accountId) {
        MemberRow row = member(courseId, accountId);
        if (row == null || !row.state().equals("ACTIVE")) throw denied();
        return row;
    }

    private static void owner(Actor actor, CourseRow row) { if (actor.role() != Role.TENANT_ADMIN || row.ownerId() != actor.id()) throw denied(); }
    private static void enabled(CourseRow row) { if (!row.enabled()) throw conflict("课程已暂停共享"); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }

    private long insertId(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey(), "Generated identity is missing").longValue();
    }

    private static String text(String value, int maximum, boolean optional) {
        String normalized = value == null && optional ? "" : value == null ? null : value.trim();
        if (normalized == null || !optional && normalized.isBlank() || normalized.length() > maximum || normalized.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw invalid("课程名称或介绍不符合长度要求");
        return normalized;
    }

    private static void quota(long value) { if (value < 1024 * 1024 || value > MAX_QUOTA) throw invalid("课程容量须在 1 MiB 到 1 GiB 之间"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private static ApplicationFault conflict(String message) { return new ApplicationFault("41000", 409, message); }
    private static ApplicationFault denied() { return new ApplicationFault("40300", 403, "没有此课程操作权限"); }
    private static ApplicationFault missing() { return new ApplicationFault("40400", 404, "课程或记录不存在"); }
    private record CourseRow(long id, long ownerId, String name, String description, long quotaBytes, long usedBytes, long materialCount, boolean enabled, long createdAt, long updatedAt) { }
    private record MemberRow(String state, String uploadStatus) { }
    private record InvitationRow(long id, long courseId, Long expiresAt, Integer maxMembers, long usedCount, Long revokedAt) { }
}
