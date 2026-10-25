package im.tlgxy.cloud.module.course;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opaque course-only objects; a course never grants access to a cloud root or personal directory. */
@Component
public class CourseFileStore {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private final Path configuredRoot;
    private final Semaphore uploads = new Semaphore(4);
    @Value("${tongligong-student-cloud.courses.minimum-free-bytes:268435456}")
    private long minimumFreeBytes = 268435456L;

    public CourseFileStore(@Value("${tongligong-student-cloud.courses.storage-root:${STUDENT_CLOUD_COURSE_STORAGE_ROOT:./data/course-materials}}") String root) {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public record Staged(Path path, long size, AtomicBoolean active) { }

    private Path root() throws IOException {
        Files.createDirectories(configuredRoot);
        Path absolute = configuredRoot.getRoot();
        for (Path part : configuredRoot) {
            absolute = absolute.resolve(part);
            if (Files.isSymbolicLink(absolute)) throw new IOException("Course storage cannot contain symlinks");
        }
        return configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private Path object(String courseId, String materialId) throws IOException {
        if (courseId == null || materialId == null || !courseId.matches("[a-f0-9]{32}") || !materialId.matches("[a-f0-9]{32}"))
            throw new IOException("Invalid course object identifier");
        Path folder = root().resolve(courseId);
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(folder)) throw new IOException("Invalid course storage directory");
        return folder.resolve(materialId + ".bin");
    }

    public Staged stage(InputStream input) {
        if (!uploads.tryAcquire()) throw new BizException("课程上传繁忙，请稍后重试");
        Path temp = null;
        try {
            Path privateRoot = root();
            if (Files.getFileStore(privateRoot).getUsableSpace() < MAX_FILE_BYTES + Math.max(0, minimumFreeBytes))
                throw new BizException("服务器剩余空间不足，课程上传暂不可用");
            temp = privateRoot.resolve("upload-" + UUID.randomUUID() + ".part");
            long size = 0;
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_FILE_BYTES) throw new BizException("每份课程资料最多 50 MB");
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) throw new BizException("不能上传空文件");
            return new Staged(temp, size, new AtomicBoolean(true));
        } catch (IOException exception) {
            removeStaging(temp); uploads.release();
            throw new BizException("课程文件暂存失败，请稍后重试");
        } catch (RuntimeException exception) {
            removeStaging(temp); uploads.release();
            throw exception;
        }
    }

    public void commit(Staged staged, String courseId, String id) {
        try {
            Path target = object(courseId, id);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Object already exists");
            // A random opaque identifier and same-directory rename avoid overwriting any user filename.
            Files.move(staged.path(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) { throw new BizException("课程文件保存失败，请稍后重试"); }
    }

    public InputStreamResource read(String courseId, String id, long expectedSize) {
        try {
            Path target = object(courseId, id);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != expectedSize)
                throw new IOException("Course object missing or changed");
            return new InputStreamResource(Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) { throw new BizException("课程文件不可用，请联系课程创建者"); }
    }

    /** Removes only the exact generated course object, never a supplied path or a recursive directory. */
    public boolean delete(String courseId, String id) {
        try { return Files.deleteIfExists(object(courseId, id)); }
        catch (IOException exception) { throw new BizException("课程文件清理失败，容量仍保留，请重试"); }
    }

    public void discard(Staged staged) {
        if (staged == null || !staged.active().compareAndSet(true, false)) return;
        try { removeStaging(staged.path()); } finally { uploads.release(); }
    }

    private void removeStaging(Path staged) {
        try {
            if (staged != null && staged.getParent().equals(configuredRoot)
                    && staged.getFileName().toString().matches("upload-[a-f0-9-]{36}\\.part")) Files.deleteIfExists(staged);
        } catch (IOException ignored) { /* Retain the isolated staging object for an administrator to inspect. */ }
    }
}
