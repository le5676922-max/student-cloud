package io.studentcloud.course;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import io.studentcloud.drive.LocalFileStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private course objects with explicit database reservations; network I/O is outside transactions. */
public final class CourseMaterialService {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_OWNER_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 6L * 1024 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SharedCourseService courses;
    private final Path root;

    public CourseMaterialService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                                 SharedCourseService courses, Path courseRoot) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock);
        this.courses = Objects.requireNonNull(courses);
        if (courseRoot != null && !courseRoot.isAbsolute()) throw new IllegalArgumentException("Course root must be an explicit absolute path");
        this.root = courseRoot == null ? null : courseRoot.normalize();
        if (root != null && root.getParent() == null) throw new IllegalArgumentException("Filesystem root cannot be a course root");
    }

    public List<MaterialView> list(Actor actor, long courseId) throws IOException {
        courses.requireRead(actor, courseId);
        store();
        return jdbc.query("SELECT * FROM sc_course_material WHERE course_id=? AND state='READY' ORDER BY id DESC LIMIT 1000",
                (r, index) -> new MaterialView(r.getLong("id"), r.getString("filename"), r.getLong("size_bytes"), r.getString("state"), r.getLong("created_at")), courseId);
    }

    public List<MaterialView> incomplete(Actor actor, long courseId) throws IOException {
        requireCreator(actor, courseId);
        store();
        return jdbc.query("SELECT * FROM sc_course_material WHERE course_id=? AND state IN('PENDING','DELETE_PENDING') ORDER BY id DESC LIMIT 1000",
                (r, index) -> new MaterialView(r.getLong("id"), r.getString("filename"), r.getLong("size_bytes"), r.getString("state"), r.getLong("created_at")), courseId);
    }

    public MaterialView upload(Actor actor, long courseId, String filename, InputStream input, long size) throws IOException {
        Objects.requireNonNull(input, "input");
        String displayName = filename(filename);
        if (size < 1 || size > MAX_FILE_BYTES) throw invalid("每份课程资料须在 1 字节至 50 MiB 之间");
        LocalFileStore files = store();
        MaterialRow reserved = transactions.execute(status -> {
            budgetLock();
            courseLock(courseId);
            CourseView course = courses.requireUpload(actor, courseId);
            if (course.usedBytes() > course.quotaBytes() - size) throw conflict("课程存储容量不足");
            Long ownerId = jdbc.queryForObject("SELECT owner_id FROM sc_shared_course WHERE id=?", Long.class, courseId);
            long ownerBytes = bytes("SELECT COALESCE(SUM(m.size_bytes),0) FROM sc_course_material m JOIN sc_shared_course c ON c.id=m.course_id WHERE c.owner_id=? AND m.state IN('PENDING','READY','DELETE_PENDING')", ownerId);
            long totalBytes = bytes("SELECT COALESCE(SUM(size_bytes),0) FROM sc_course_material WHERE state IN('PENDING','READY','DELETE_PENDING')");
            if (ownerBytes > MAX_OWNER_BYTES - size || totalBytes > MAX_TOTAL_BYTES - size) throw conflict("课程创建者或全站课程容量不足");
            if (bytes("SELECT COUNT(*) FROM sc_course_material WHERE course_id=? AND state IN('PENDING','READY','DELETE_PENDING')", courseId) >= 1000
                    || bytes("SELECT COUNT(*) FROM sc_course_material WHERE state IN('PENDING','READY','DELETE_PENDING')") >= 100_000)
                throw conflict("课程资料数量已达上限");
            String key = UUID.randomUUID().toString().replace("-", "") + ".bin";
            long now = clock.millis();
            GeneratedKeyHolder generated = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO sc_course_material(course_id,filename,object_key,size_bytes,state,created_at,updated_at) VALUES(?,?,?,?,'PENDING',?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, courseId);
                statement.setString(2, displayName);
                statement.setString(3, key);
                statement.setLong(4, size);
                statement.setLong(5, now);
                statement.setLong(6, now);
                return statement;
            }, generated);
            long id = Objects.requireNonNull(generated.getKey()).longValue();
            if (jdbc.update("UPDATE sc_shared_course SET used_bytes=used_bytes+?,updated_at=? WHERE id=? AND used_bytes<=quota_bytes-?", size, now, courseId, size) != 1)
                throw conflict("课程容量已变化，请重试");
            return new MaterialRow(id, courseId, displayName, key, size, "PENDING", now);
        });
        boolean published = false;
        try {
            try { files.createDirectory(folder(courseId)); }
            catch (FileAlreadyExistsException concurrentDirectory) {
                if (files.stat(folder(courseId)).filter(FileRecord::directory).isEmpty()) throw concurrentDirectory;
            }
            FileRecord result = files.write(object(reserved), input, size, false);
            published = true;
            if (result.size() != size) throw new IOException("Course upload size could not be verified");
            return transactions.execute(status -> {
                budgetLock();
                courseLock(courseId);
                courses.requireUpload(actor, courseId);
                if (jdbc.update("UPDATE sc_course_material SET state='READY',updated_at=? WHERE id=? AND course_id=? AND state='PENDING'", clock.millis(), reserved.id(), courseId) != 1)
                    throw conflict("上传记录已变化，请重新核验");
                jdbc.update("UPDATE sc_shared_course SET material_count=material_count+1,updated_at=? WHERE id=?", clock.millis(), courseId);
                return reserved.view("READY");
            });
        } catch (IOException | RuntimeException failure) {
            // A failed cleanup remains charged and visible, never reported as an atomic rollback.
            try {
                if (markFailed(reserved)) {
                    if (published) cleanup(files, reserved);
                    // A failed write owns its staging file, not an unexpected pre-existing target.
                    // Keep such a target charged for explicit administrator reconciliation.
                    if (files.stat(object(reserved)).isEmpty()) release(reserved);
                }
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public MaterialDownload download(Actor actor, long courseId, long materialId) throws IOException {
        courses.requireRead(actor, courseId);
        MaterialRow row = material(courseId, materialId);
        if (!row.state().equals("READY")) throw missing();
        LocalFileStore files = store();
        FileRecord physical = files.stat(object(row)).orElseThrow(() -> new IOException("Course object is unavailable"));
        if (physical.directory() || physical.size() != row.size()) throw new IOException("Course object no longer matches its metadata");
        InputStream content = files.read(object(row));
        try {
            // Opening a file may block; do not return a newly revoked grant's stream.
            courses.requireRead(actor, courseId);
            MaterialRow fresh = material(courseId, materialId);
            if (!fresh.state().equals("READY")) throw missing();
            return new MaterialDownload(row.view("READY"), content);
        } catch (RuntimeException failure) {
            try { content.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public void delete(Actor actor, long courseId, long materialId) throws IOException {
        LocalFileStore files = store();
        MaterialRow row = transactions.execute(status -> {
            budgetLock();
            courseLock(courseId);
            requireCreator(actor, courseId);
            MaterialRow existing = material(courseId, materialId);
            if (existing.state().equals("PENDING")) throw conflict("此上传尚未确认结束，不能提前释放容量；请先核验上传进程");
            if (existing.state().equals("DELETED")) return existing;
            if (existing.state().equals("READY")) {
                jdbc.update("UPDATE sc_course_material SET state='DELETE_PENDING',updated_at=? WHERE id=?", clock.millis(), existing.id());
                if (jdbc.update("UPDATE sc_shared_course SET material_count=material_count-1,updated_at=? WHERE id=? AND material_count>0", clock.millis(), courseId) != 1)
                    throw conflict("课程资料计数需要核验");
            } else if (!existing.state().equals("DELETE_PENDING")) throw conflict("课程资料状态需要核验");
            return existing;
        });
        if (row.state().equals("DELETED")) return;
        cleanup(files, row);
        release(row);
    }

    private boolean markFailed(MaterialRow row) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            budgetLock();
            courseLock(row.courseId());
            MaterialRow fresh = material(row.courseId(), row.id());
            if (fresh.state().equals("READY") || fresh.state().equals("DELETED")) return false;
            if (!fresh.state().equals("PENDING") && !fresh.state().equals("DELETE_PENDING")) throw conflict("上传状态需要核验");
            jdbc.update("UPDATE sc_course_material SET state='DELETE_PENDING',updated_at=? WHERE id=? AND state='PENDING'", clock.millis(), row.id());
            return true;
        }));
    }

    private void release(MaterialRow row) {
        transactions.executeWithoutResult(status -> {
            budgetLock();
            courseLock(row.courseId());
            MaterialRow fresh = material(row.courseId(), row.id());
            if (fresh.state().equals("DELETED")) return;
            if (!fresh.state().equals("DELETE_PENDING")) throw conflict("资料状态未允许释放容量");
            if (jdbc.update("UPDATE sc_shared_course SET used_bytes=used_bytes-?,updated_at=? WHERE id=? AND used_bytes>=?", row.size(), clock.millis(), row.courseId(), row.size()) != 1)
                throw conflict("课程容量记录需要核验");
            jdbc.update("UPDATE sc_course_material SET state='DELETED',updated_at=? WHERE id=? AND state='DELETE_PENDING'", clock.millis(), row.id());
        });
    }

    private static void cleanup(LocalFileStore files, MaterialRow row) throws IOException {
        var existing = files.stat(object(row));
        if (existing.isEmpty()) return;
        if (existing.get().directory()) throw new IOException("Unexpected directory at private course object");
        files.delete(object(row));
        if (files.stat(object(row)).isPresent()) throw new IOException("Course object cleanup is incomplete");
    }

    private void requireCreator(Actor actor, long courseId) {
        if (!courses.requireRead(actor, courseId).creator()) throw new ApplicationFault("40300", 403, "只有课程创建者可以管理资料");
    }

    private void budgetLock() { jdbc.queryForObject("SELECT id FROM sc_course_storage_budget WHERE id=1 FOR UPDATE", Integer.class); }
    private void courseLock(long courseId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM sc_shared_course WHERE id=? AND deleted_at IS NULL FOR UPDATE", Long.class, courseId);
        if (ids.size() != 1) throw missing();
    }

    private long bytes(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }

    private MaterialRow material(long courseId, long id) {
        List<MaterialRow> rows = jdbc.query("SELECT id,course_id,filename,object_key,size_bytes,state,created_at FROM sc_course_material WHERE id=? AND course_id=?",
                (r, index) -> new MaterialRow(r.getLong("id"), r.getLong("course_id"), r.getString("filename"), r.getString("object_key"), r.getLong("size_bytes"), r.getString("state"), r.getLong("created_at")), id, courseId);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private LocalFileStore store() throws IOException {
        if (root == null) throw new ApplicationFault("50300", 503, "尚未配置独立课程文件目录");
        return new LocalFileStore(root, 2000, MAX_FILE_BYTES);
    }

    private static DrivePath folder(long courseId) {
        if (courseId <= 0) throw invalid("课程编号无效");
        return DrivePath.of("/course-" + courseId);
    }

    private static DrivePath object(MaterialRow row) {
        if (!row.objectKey().matches("[0-9a-f]{32}\\.bin")) throw conflict("课程文件记录需要核验");
        return folder(row.courseId()).child(row.objectKey());
    }

    private static String filename(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
                || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw invalid("资料文件名无效");
        return name.trim();
    }

    private static ApplicationFault invalid(String message) { return new ApplicationFault("41000", 400, message); }
    private static ApplicationFault conflict(String message) { return new ApplicationFault("41000", 409, message); }
    private static ApplicationFault missing() { return new ApplicationFault("40400", 404, "课程资料不存在或尚未完成上传"); }
    private record MaterialRow(long id, long courseId, String filename, String objectKey, long size, String state, long createdAt) {
        MaterialView view(String status) { return new MaterialView(id, filename, size, status, createdAt); }
    }
}
