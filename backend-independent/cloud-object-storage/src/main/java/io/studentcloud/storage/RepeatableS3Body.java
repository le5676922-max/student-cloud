package io.studentcloud.storage;

import io.studentcloud.common.ApplicationFault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** SDK signing and Apache transport may each open the body even with retries disabled. */
final class RepeatableS3Body implements AutoCloseable {
    private final Path file;

    private RepeatableS3Body(Path file) { this.file = file; }

    static RepeatableS3Body stage(InputStream input, long declaredSize) {
        Path file = null;
        try {
            file = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? Files.createTempFile("studentcloud-s3-", ".body", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile("studentcloud-s3-", ".body");
            long actual = 0;
            int emptyReads = 0;
            try (OutputStream output = Files.newOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) {
                        if (++emptyReads > 1024) throw new ApplicationFault("OBJECT_STORE_STAGING_INPUT", 400, "上传数据流未产生有效数据，请重新提交文件");
                        continue;
                    }
                    actual += count;
                    if (actual > declaredSize) throw lengthFailure();
                    output.write(buffer, 0, count);
                }
            }
            if (actual != declaredSize) throw lengthFailure();
            return new RepeatableS3Body(file);
        } catch (IOException failure) {
            removeFailedStage(file);
            throw new ApplicationFault("OBJECT_STORE_STAGING", 503, "无法准备对象存储上传数据，请核对本地暂存空间");
        } catch (RuntimeException failure) {
            removeFailedStage(file);
            throw failure;
        }
        // The caller owns input. Only this additional private staging file belongs to the adapter.
    }

    Path file() { return file; }

    @Override public void close() {
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { throw new ApplicationFault("OBJECT_STORE_STAGING_CLEANUP", 503, "本地上传暂存清理未完成，请联系管理员核对"); }
    }

    private static void removeFailedStage(Path file) {
        if (file != null) try { Files.deleteIfExists(file); }
        catch (IOException failure) { throw new ApplicationFault("OBJECT_STORE_STAGING_CLEANUP", 503, "本地上传暂存清理未完成，请联系管理员核对"); }
    }

    private static ApplicationFault lengthFailure() {
        return new ApplicationFault("OBJECT_STORE_LENGTH", 400, "上传数据的实际长度与声明长度不一致");
    }
}
