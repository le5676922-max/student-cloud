package im.tlgxy.cloud.module.course;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Real cross-workspace courses. Course membership never changes workspace or personal-cloud grants. */
@Service
public class SharedCourseService {
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final CourseFileStore files;
    private final TransactionTemplate transactions;
    private final long maximumQuota;
    @Value("${tongligong-student-cloud.courses.max-owner-bytes:5368709120}")
    private long maximumOwnerBytes = 5368709120L;
    @Value("${tongligong-student-cloud.courses.max-total-bytes:21474836480}")
    private long maximumTotalBytes = 21474836480L;

    public SharedCourseService(JdbcTemplate jdbc, UserService users, CourseFileStore files,
                               PlatformTransactionManager manager,
                               @Value("${tongligong-student-cloud.courses.max-course-bytes:1073741824}") long maximumQuota) {
        this.jdbc = jdbc; this.users = users; this.files = files;
        this.transactions = new TransactionTemplate(manager);
        this.maximumQuota = Math.max(1, maximumQuota);
    }

    public record Course(String id, int ownerUserId, int tenantId, String name, String description,
                         boolean enabled, long quotaBytes, long usedBytes, long createdAt,
                         boolean creator, String uploadStatus, long memberCount, long materialCount) { }
    public record Created(Course course, String joinCode) { }
    public record Draft(String name, String description, long quotaBytes) { }
    public record Material(String id, String filename, long sizeBytes, long createdAt, boolean own) { }
    public record Member(int userId, String nickname, String uploadStatus, long joinedAt) { }
    public record Download(String filename, long size, InputStreamResource resource) { }
    public record Invitation(String id, long createdAt, Long expiresAt, long revokedAt, long usedCount, Integer maxMembers) { }
    public record CreatedInvitation(String id, String code, long createdAt, Long expiresAt, long usedCount, Integer maxMembers) { }
    public record InvitationPreview(String id, String name, String description, boolean joined, boolean creator, Long expiresAt) { }
    private record Row(String id, int owner, int tenant, String name, String description, String codeHash,
                       boolean enabled, long quota, long used, long created) { }
    private record Membership(String status, String uploadStatus) { }
    private record FileRow(String id, String courseId, int uploader, String filename, long size, String state, long created) { }
    private record InviteRow(String id, String courseId, String codeHash, long created, Long expires, long revoked, long used, Integer maxMembers) { }
    private record InviteTarget(Row course, InviteRow invitation) { }
    private static final RowMapper<Row> COURSE = (r, i) -> new Row(r.getString("id"), r.getInt("owner_user_id"),
            r.getInt("tenant_id"), r.getString("name"), r.getString("description"), r.getString("code_hash"),
            r.getInt("enabled") == 1, r.getLong("quota_bytes"), r.getLong("used_bytes"), r.getLong("created_at"));
    private static final RowMapper<FileRow> FILE = (r, i) -> new FileRow(r.getString("id"), r.getString("course_id"),
            r.getInt("uploader_user_id"), r.getString("filename"), r.getLong("size_bytes"), r.getString("state"), r.getLong("created_at"));
    private static final RowMapper<InviteRow> INVITATION = (r, i) -> new InviteRow(r.getString("id"), r.getString("course_id"),
            r.getString("code_hash"), r.getLong("created_at"), r.getObject("expires_at") == null ? null : r.getLong("expires_at"), r.getLong("revoked_at"), r.getLong("used_count"),
            r.getObject("max_members") == null ? null : r.getInt("max_members"));

    private User actor(int actorId) {
        User actor = users.requireLoginUser(actorId);
        if (actor == null || !Boolean.TRUE.equals(actor.getEnable()) || actorId <= 2 || actor.getTenantId() == null
                || (actor.getRole() != UserRoleEnum.USER && actor.getRole() != UserRoleEnum.TENANT_ADMIN)) denied();
        return actor;
    }

    private Row row(String id) {
        validateId(id);
        List<Row> rows = jdbc.query("SELECT * FROM shared_course WHERE id=?", COURSE, id);
        if (rows.isEmpty()) throw new BizException("课程不存在或无权访问");
        return rows.getFirst();
    }

    /** The row update serializes approval, revocation, capacity reservation and code rotation across instances. */
    private Row locked(String id) {
        validateId(id);
        if (jdbc.update("UPDATE shared_course SET version=version+1 WHERE id=?", id) != 1)
            throw new BizException("课程不存在或无权访问");
        return row(id);
    }

    private Membership membership(String id, int actorId) {
        var rows = jdbc.query("SELECT status,upload_status FROM shared_course_member WHERE course_id=? AND user_id=?",
                (r, i) -> new Membership(r.getString("status"), r.getString("upload_status")), id, actorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void owner(Row course, int actorId) {
        User current = actor(actorId);
        if (course.owner() != actorId || current.getRole() != UserRoleEnum.TENANT_ADMIN
                || !Objects.equals(current.getTenantId(), course.tenant())) denied();
    }

    private void activeOwner(Row course) {
        User creator = actor(course.owner());
        if (creator.getRole() != UserRoleEnum.TENANT_ADMIN || !Objects.equals(creator.getTenantId(), course.tenant())) denied();
    }

    private void read(Row course, int actorId) {
        actor(actorId); activeOwner(course);
        if (!course.enabled()) throw new BizException("课程已暂停共享");
        if (course.owner() == actorId) return;
        Membership membership = membership(course.id(), actorId);
        if (membership == null || !"JOINED".equals(membership.status())) denied();
    }

    private void upload(Row course, int actorId) {
        read(course, actorId);
        if (course.owner() != actorId && !"APPROVED".equals(membership(course.id(), actorId).uploadStatus()))
            throw new BizException("请先申请上传权限，等待课程创建者批准");
    }

    /** May be used by dynamic knowledge retrieval; every call checks live membership and creator state. */
    public boolean canRead(int actorId, String courseId) {
        try { read(row(courseId), actorId); return true; } catch (BizException denied) { return false; }
    }

    /** Global moderation may validate a scope without obtaining course-member access to any files. */
    public boolean isActive(String courseId) {
        try { Row course = row(courseId); activeOwner(course); return course.enabled(); }
        catch (BizException denied) { return false; }
    }

    public List<Course> list(int actorId) {
        actor(actorId);
        return jdbc.query("SELECT c.* FROM shared_course c LEFT JOIN shared_course_member m ON m.course_id=c.id AND m.user_id=? "
                + "WHERE c.owner_user_id=? OR m.status='JOINED' ORDER BY c.created_at DESC,c.id LIMIT 100", COURSE, actorId, actorId)
                .stream().map(c -> view(c, actorId)).toList();
    }

    public Course get(int actorId, String courseId) {
        Row course = row(courseId);
        if (course.owner() == actorId) owner(course, actorId); else read(course, actorId);
        return view(course, actorId);
    }

    private Course view(Row c, int actorId) {
        Membership member = membership(c.id(), actorId);
        return new Course(c.id(), c.owner(), c.tenant(), c.name(), c.description(), c.enabled(), c.quota(), c.used(), c.created(),
                c.owner() == actorId, c.owner() == actorId ? "OWNER" : member == null ? "NONE" : member.uploadStatus(),
                count("SELECT COUNT(*) FROM shared_course_member WHERE course_id=? AND status='JOINED'", c.id()),
                count("SELECT COUNT(*) FROM shared_course_material WHERE course_id=? AND state='READY'", c.id()));
    }

    public Created create(int actorId, Draft draft) {
        User creator = actor(actorId);
        if (creator.getRole() != UserRoleEnum.TENANT_ADMIN) denied();
        validateDraft(draft);
        return transactions.execute(status -> {
            User currentCreator = creator(actorId);
            jdbc.update("UPDATE `user` SET id=id WHERE id=?", actorId);
            if (count("SELECT COUNT(*) FROM shared_course WHERE owner_user_id=?", actorId) >= 50)
                throw new BizException("每位管理员最多创建 50 门共享课程");
            String id = id(); String code = id(); long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO shared_course(id,owner_user_id,tenant_id,name,description,code_hash,quota_bytes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, actorId, currentCreator.getTenantId(), draft.name().trim(), nullableText(draft.description()), hash(code), draft.quotaBytes(), now, now);
            return new Created(view(row(id), actorId), code);
        });
    }

    private User creator(int actorId) {
        User user = actor(actorId); if (user.getRole() != UserRoleEnum.TENANT_ADMIN) denied(); return user;
    }

    public Course edit(int actorId, String courseId, Draft draft, boolean enabled) {
        validateDraft(draft);
        return transactions.execute(status -> {
            Row course = locked(courseId); owner(course, actorId);
            if (draft.quotaBytes() < course.used()) throw new BizException("容量上限不能小于已用容量（含处理中上传）");
            jdbc.update("UPDATE shared_course SET name=?,description=?,quota_bytes=?,enabled=?,updated_at=? WHERE id=?",
                    draft.name().trim(), nullableText(draft.description()), draft.quotaBytes(), enabled ? 1 : 0, System.currentTimeMillis(), courseId);
            return view(row(courseId), actorId);
        });
    }

    public String rotateCode(int actorId, String courseId) {
        return transactions.execute(status -> {
            Row course = locked(courseId); owner(course, actorId);
            String code = id();
            jdbc.update("UPDATE shared_course SET code_hash=?,updated_at=? WHERE id=?", hash(code), System.currentTimeMillis(), courseId);
            return code;
        });
    }

    /** Invitations coexist with the legacy course code; issuing a link never invalidates another link. */
    public CreatedInvitation createInvitation(int actorId, String courseId) {
        return createInvitation(actorId, courseId, null);
    }

    public CreatedInvitation createInvitation(int actorId, String courseId, Long expiresAt) {
        return createInvitation(actorId, courseId, expiresAt, null);
    }

    public CreatedInvitation createInvitation(int actorId, String courseId, Long expiresAt, Integer maxMembers) {
        return transactions.execute(status -> {
            Row course = locked(courseId); owner(course, actorId);
            if (!course.enabled()) throw new BizException("课程已暂停共享");
            long now = System.currentTimeMillis();
            if (expiresAt != null && (expiresAt <= now || expiresAt > 253402300799999L))
                throw new BizException("请选择未来的有效时间，或设为永久有效");
            if (maxMembers != null && (maxMembers < 1 || maxMembers > 500))
                throw new BizException("邀请人数上限须为 1 至 500 人，或设为不限人数");
            String invitationId = id(), code = id();
            jdbc.update("INSERT INTO shared_course_invitation(id,course_id,code_hash,created_at,expires_at,max_members) VALUES(?,?,?,?,?,?)",
                    invitationId, courseId, hash(code), now, expiresAt, maxMembers);
            return new CreatedInvitation(invitationId, code, now, expiresAt, 0, maxMembers);
        });
    }

    public List<Invitation> invitations(int actorId, String courseId) {
        return invitations(actorId, courseId, 0, 20);
    }

    public List<Invitation> invitations(int actorId, String courseId, int offset, int limit) {
        owner(row(courseId), actorId);
        if (offset < 0 || limit < 1 || limit > 100) throw new BizException("邀请列表分页参数无效");
        return jdbc.query("SELECT * FROM shared_course_invitation WHERE course_id=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                INVITATION, courseId, limit, offset).stream().map(i -> new Invitation(i.id(), i.created(), i.expires(), i.revoked(), i.used(), i.maxMembers())).toList();
    }

    public void revokeInvitation(int actorId, String courseId, String invitationId) {
        validateId(invitationId);
        transactions.executeWithoutResult(status -> {
            Row course = locked(courseId); owner(course, actorId);
            if (count("SELECT COUNT(*) FROM shared_course_invitation WHERE id=? AND course_id=?", invitationId, courseId) != 1)
                throw new BizException("课程邀请不存在或无权操作");
            jdbc.update("UPDATE shared_course_invitation SET revoked_at=? WHERE id=? AND course_id=? AND revoked_at=0",
                    System.currentTimeMillis(), invitationId, courseId);
        });
    }

    /** Only the valid invitation bearer can preview minimal course information; no membership is created. */
    public InvitationPreview previewInvitation(int actorId, String code) {
        actor(actorId);
        InviteTarget target = invitationTarget(normalizedCode(code));
        Row course = target.course();
        InviteRow invitation = validateInvitation(target, course, false);
        Membership member = membership(course.id(), actorId);
        if (member != null && "REMOVED".equals(member.status())) throw new BizException("你已被移出课程，请联系课程创建者");
        boolean creator = course.owner() == actorId;
        boolean joined = creator || (member != null && "JOINED".equals(member.status()));
        if (!joined) requireInvitationCapacity(invitation, actorId);
        return new InvitationPreview(course.id(), course.name(), course.description(), joined,
                creator, invitation == null ? null : invitation.expires());
    }

    private String normalizedCode(String code) {
        if (code == null || !code.matches("[a-fA-F0-9]{32}")) throw new BizException("课程邀请码无效");
        return code.toLowerCase(java.util.Locale.ROOT);
    }

    private InviteTarget invitationTarget(String code) {
        String codeHash = hash(code);
        List<Row> legacy = jdbc.query("SELECT * FROM shared_course WHERE code_hash=?", COURSE, codeHash);
        if (!legacy.isEmpty()) return new InviteTarget(legacy.getFirst(), null);
        List<InviteRow> invitations = jdbc.query("SELECT * FROM shared_course_invitation WHERE code_hash=?", INVITATION, codeHash);
        if (invitations.isEmpty()) throw new BizException("课程邀请码无效、已过期或已撤销");
        InviteRow invitation = invitations.getFirst();
        return new InviteTarget(row(invitation.courseId()), invitation);
    }

    /** Re-read invitation state after obtaining the course lock, shared with revocation and membership changes. */
    private InviteRow validateInvitation(InviteTarget target, Row current, boolean locked) {
        InviteRow invitation = null;
        if (target.invitation() == null) {
            if (!Objects.equals(current.codeHash(), target.course().codeHash())) throw new BizException("课程邀请码已更新");
        } else {
            InviteRow original = target.invitation();
            // The real version change is a current read under MySQL REPEATABLE READ and
            // returns one changed row regardless of the driver's useAffectedRows setting.
            if (locked && jdbc.update("UPDATE shared_course_invitation SET version=version+1 WHERE id=? AND course_id=? AND code_hash=? AND revoked_at=0 AND (expires_at IS NULL OR expires_at>?)",
                    original.id(), current.id(), original.codeHash(), System.currentTimeMillis()) != 1)
                throw new BizException("课程邀请已过期或已撤销，请向创建者获取新邀请");
            List<InviteRow> fresh = jdbc.query("SELECT * FROM shared_course_invitation WHERE id=? AND course_id=? AND code_hash=?",
                    INVITATION, original.id(), current.id(), original.codeHash());
            boolean active = !fresh.isEmpty() && fresh.getFirst().revoked() == 0 && (fresh.getFirst().expires() == null || fresh.getFirst().expires() > System.currentTimeMillis());
            if (!active)
                throw new BizException("课程邀请已过期或已撤销，请向创建者获取新邀请");
            invitation = fresh.getFirst();
        }
        activeOwner(current);
        if (!current.enabled()) throw new BizException("课程已暂停共享");
        return invitation;
    }

    private boolean invitationAlreadyUsed(InviteRow invitation, int actorId) {
        return count("SELECT COUNT(*) FROM shared_course_invitation_use WHERE invitation_id=? AND user_id=?", invitation.id(), actorId) != 0;
    }

    private void requireInvitationCapacity(InviteRow invitation, int actorId) {
        if (invitation != null && invitation.maxMembers() != null && invitation.used() >= invitation.maxMembers()
                && !invitationAlreadyUsed(invitation, actorId))
            throw new BizException("该邀请的可加入人数已用完，请向课程创建者获取新邀请");
    }

    public Course join(int actorId, String code) {
        actor(actorId);
        String normalized = normalizedCode(code);
        InviteTarget target = invitationTarget(normalized);
        return transactions.execute(status -> {
            // Resolve the token outside the transaction so no pre-lock consistent-read
            // snapshot can hide a concurrent membership change while waiting for this lock.
            Row course = locked(target.course().id());
            actor(actorId);
            InviteRow invitation = validateInvitation(target, course, true);
            if (course.owner() == actorId) return view(course, actorId);
            Membership existing = membership(course.id(), actorId);
            if (existing != null && "REMOVED".equals(existing.status())) throw new BizException("你已被移出课程，请联系课程创建者");
            if (existing != null && "JOINED".equals(existing.status())) return view(course, actorId);
            requireInvitationCapacity(invitation, actorId);
            if (count("SELECT COUNT(*) FROM shared_course_member WHERE course_id=? AND status='JOINED'", course.id()) >= 500)
                throw new BizException("课程成员已达上限");
            if (count("SELECT COUNT(*) FROM shared_course_member WHERE user_id=? AND status='JOINED'", actorId) >= 50)
                throw new BizException("每个账号最多加入 50 门共享课程");
            long now = System.currentTimeMillis();
            if (existing == null) jdbc.update("INSERT INTO shared_course_member(course_id,user_id,joined_at,updated_at) VALUES(?,?,?,?)", course.id(), actorId, now, now);
            else jdbc.update("UPDATE shared_course_member SET status='JOINED',upload_status='NONE',joined_at=?,updated_at=? WHERE course_id=? AND user_id=?", now, now, course.id(), actorId);
            if (invitation != null && !invitationAlreadyUsed(invitation, actorId)) {
                if (jdbc.update("UPDATE shared_course_invitation SET used_count=used_count+1 WHERE id=? AND course_id=? AND (max_members IS NULL OR used_count<max_members)", invitation.id(), course.id()) != 1)
                    throw new BizException("该邀请的可加入人数已用完，请向课程创建者获取新邀请");
                jdbc.update("INSERT INTO shared_course_invitation_use(invitation_id,user_id,joined_at) VALUES(?,?,?)", invitation.id(), actorId, now);
            }
            return view(course, actorId);
        });
    }

    public void leave(int actorId, String courseId) {
        actor(actorId);
        transactions.executeWithoutResult(status -> {
            Row course = locked(courseId);
            if (course.owner() == actorId) throw new BizException("创建者不能退出自己的课程，可暂停共享");
            jdbc.update("UPDATE shared_course_member SET status='LEFT',upload_status='NONE',updated_at=? WHERE course_id=? AND user_id=?",
                    System.currentTimeMillis(), courseId, actorId);
        });
    }

    public void requestUpload(int actorId, String courseId) {
        transactions.executeWithoutResult(status -> {
            Row course = locked(courseId); read(course, actorId);
            if (course.owner() == actorId) return;
            Membership member = membership(courseId, actorId);
            if ("APPROVED".equals(member.uploadStatus()) || "PENDING".equals(member.uploadStatus())) return;
            jdbc.update("UPDATE shared_course_member SET upload_status='PENDING',updated_at=? WHERE course_id=? AND user_id=? AND status='JOINED'",
                    System.currentTimeMillis(), courseId, actorId);
        });
    }

    public List<Member> members(int actorId, String courseId) {
        owner(row(courseId), actorId);
        return jdbc.query("SELECT m.user_id,COALESCE(u.nickname,u.username) nickname,m.upload_status,m.joined_at FROM shared_course_member m JOIN `user` u ON u.id=m.user_id "
                + "WHERE m.course_id=? AND m.status='JOINED' ORDER BY CASE WHEN m.upload_status='PENDING' THEN 0 ELSE 1 END,m.joined_at LIMIT 500",
                (r, i) -> new Member(r.getInt("user_id"), r.getString("nickname"), r.getString("upload_status"), r.getLong("joined_at")), courseId);
    }

    public void reviewUpload(int actorId, String courseId, int memberId, boolean approve) {
        transactions.executeWithoutResult(status -> {
            Row course = locked(courseId); owner(course, actorId);
            if (memberId == actorId) denied();
            Membership member = membership(courseId, memberId);
            if (member == null || !"JOINED".equals(member.status())) throw new BizException("该成员尚未加入课程");
            if (approve && !"PENDING".equals(member.uploadStatus()) && !"APPROVED".equals(member.uploadStatus()))
                throw new BizException("请等待成员先申请上传权限");
            jdbc.update("UPDATE shared_course_member SET upload_status=?,updated_at=? WHERE course_id=? AND user_id=?",
                    approve ? "APPROVED" : "REJECTED", System.currentTimeMillis(), courseId, memberId);
        });
    }

    public void removeMember(int actorId, String courseId, int memberId) {
        transactions.executeWithoutResult(status -> {
            Row course = locked(courseId); owner(course, actorId);
            if (memberId == actorId) denied();
            jdbc.update("UPDATE shared_course_member SET status='REMOVED',upload_status='NONE',updated_at=? WHERE course_id=? AND user_id=?",
                    System.currentTimeMillis(), courseId, memberId);
        });
    }

    public List<Material> materials(int actorId, String courseId) {
        read(row(courseId), actorId);
        return jdbc.query("SELECT * FROM shared_course_material WHERE course_id=? AND state='READY' ORDER BY created_at DESC,id LIMIT 1000", FILE, courseId)
                .stream().map(f -> new Material(f.id(), f.filename(), f.size(), f.created(), f.uploader() == actorId)).toList();
    }

    public Material upload(int actorId, String courseId, String filename, InputStream input) {
        upload(row(courseId), actorId);
        String safeName = filename(filename);
        CourseFileStore.Staged staged = files.stage(input);
        String materialId = id(); boolean reserved = false; boolean committed = false;
        try {
            transactions.executeWithoutResult(status -> {
                lockBudget();
                Row course = locked(courseId); upload(course, actorId);
                if (count("SELECT COUNT(*) FROM shared_course_material WHERE course_id=?", courseId) >= 1000)
                    throw new BizException("每门课程最多保存 1000 份资料");
                if (jdbc.update("UPDATE shared_course SET used_bytes=used_bytes+? WHERE id=? AND used_bytes<=quota_bytes-?", staged.size(), courseId, staged.size()) != 1)
                    throw new BizException("课程空间容量不足，请联系课程创建者");
                Long ownerUsed = jdbc.queryForObject("SELECT COALESCE(SUM(used_bytes),0) FROM shared_course WHERE owner_user_id=?", Long.class, course.owner());
                if (ownerUsed != null && ownerUsed > maximumOwnerBytes) throw new BizException("课程创建者的课程总容量已达上限");
                if (jdbc.update("UPDATE shared_course_storage_usage SET used_bytes=used_bytes+? WHERE id=1 AND used_bytes<=?-?", staged.size(), maximumTotalBytes, staged.size()) != 1)
                    throw new BizException("服务器课程资料总容量已达上限，请联系全局管理员");
                jdbc.update("INSERT INTO shared_course_material(id,course_id,uploader_user_id,filename,size_bytes,state,created_at) VALUES(?,?,?,?,?,'PENDING',?)",
                        materialId, courseId, actorId, safeName, staged.size(), System.currentTimeMillis());
            });
            reserved = true;
            files.commit(staged, courseId, materialId); committed = true;
            transactions.executeWithoutResult(status -> {
                Row course = locked(courseId); upload(course, actorId);
                if (jdbc.update("UPDATE shared_course_material SET state='READY' WHERE id=? AND state='PENDING'", materialId) != 1)
                    throw new BizException("上传记录已变化，请刷新后确认");
            });
            return new Material(materialId, safeName, staged.size(), System.currentTimeMillis(), true);
        } catch (RuntimeException failure) {
            // Never release accounting until the actual object is removed. A cleanup failure stays visible to the creator.
            if (committed) files.delete(courseId, materialId);
            if (reserved) rollbackUpload(courseId, materialId, staged.size());
            throw failure;
        } finally { files.discard(staged); }
    }

    private void rollbackUpload(String courseId, String id, long size) {
        transactions.executeWithoutResult(status -> {
            lockBudget();
            locked(courseId);
            if (jdbc.update("DELETE FROM shared_course_material WHERE id=? AND course_id=? AND state='PENDING'", id, courseId) == 1) {
                jdbc.update("UPDATE shared_course SET used_bytes=used_bytes-? WHERE id=? AND used_bytes>=?", size, courseId, size);
                jdbc.update("UPDATE shared_course_storage_usage SET used_bytes=used_bytes-? WHERE id=1 AND used_bytes>=?", size, size);
            }
        });
    }

    public Download download(int actorId, String courseId, String materialId) {
        read(row(courseId), actorId);
        FileRow file = file(courseId, materialId);
        if (!"READY".equals(file.state())) throw new BizException("资料尚未准备好");
        return new Download(file.filename(), file.size(), files.read(courseId, materialId, file.size()));
    }

    public void deleteMaterial(int actorId, String courseId, String materialId) {
        transactions.executeWithoutResult(status -> {
            lockBudget();
            Row course = locked(courseId); owner(course, actorId);
            FileRow file = file(courseId, materialId);
            // Small bounded local operation; keeping this row lock prevents concurrent delete double accounting.
            files.delete(courseId, materialId);
            jdbc.update("DELETE FROM shared_course_material WHERE course_id=? AND id=?", courseId, materialId);
            jdbc.update("UPDATE shared_course SET used_bytes=used_bytes-? WHERE id=? AND used_bytes>=?", file.size(), courseId, file.size());
            jdbc.update("UPDATE shared_course_storage_usage SET used_bytes=used_bytes-? WHERE id=1 AND used_bytes>=?", file.size(), file.size());
        });
    }

    public List<Material> incompleteUploads(int actorId, String courseId) {
        owner(row(courseId), actorId);
        return jdbc.query("SELECT * FROM shared_course_material WHERE course_id=? AND state='PENDING' ORDER BY created_at LIMIT 1000", FILE, courseId)
                .stream().map(f -> new Material(f.id(), f.filename(), f.size(), f.created(), f.uploader() == actorId)).toList();
    }

    private FileRow file(String courseId, String materialId) {
        validateId(materialId);
        var rows = jdbc.query("SELECT * FROM shared_course_material WHERE course_id=? AND id=?", FILE, courseId, materialId);
        if (rows.isEmpty()) throw new BizException("课程资料不存在或已删除"); return rows.getFirst();
    }
    private long count(String sql, Object... args) { Long count = jdbc.queryForObject(sql, Long.class, args); return count == null ? 0 : count; }
    private void lockBudget() {
        if (jdbc.update("UPDATE shared_course_storage_usage SET used_bytes=used_bytes WHERE id=1") != 1)
            throw new BizException("课程容量账本不可用，请联系全局管理员");
    }
    private void validateDraft(Draft draft) {
        if (draft == null || draft.name() == null || draft.name().trim().isEmpty() || draft.name().trim().length() > 120
                || nullableText(draft.description()).length() > 1000) throw new BizException("课程名称为 1–120 字，介绍最多 1000 字");
        if (draft.quotaBytes() < 1 || draft.quotaBytes() > maximumQuota) throw new BizException("课程容量必须在服务器允许范围内");
    }
    private static String filename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 240 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(c -> c < 32 || c == 127) || filename.equals(".") || filename.equals(".."))
            throw new BizException("文件名不正确");
        return filename;
    }
    private static void validateId(String id) { if (id == null || !id.matches("[a-f0-9]{32}")) throw new BizException("课程标识不正确"); }
    private static String nullableText(String value) { return value == null ? "" : value.trim(); }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void denied() { throw new BizException("无权访问该课程或执行此操作"); }
}
