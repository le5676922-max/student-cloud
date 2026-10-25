package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.enums.FileTypeEnum;
import im.tlgxy.cloud.module.storage.model.param.LocalParam;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.storage.service.DirectoryMetadataProvider;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import im.tlgxy.cloud.module.storage.service.base.AbstractProxyTransferService;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Member writes are serialized by a database row, including writes from another app instance.
 * Usage is measured from raw storage metadata, never filtered/cached browser listings.
 * Unmeasurable/oversized trees fail closed rather than silently bypassing capacity limits. */
@Service
@RequiredArgsConstructor
public class MemberStorageQuotaService {
    public static final long DEFAULT_QUOTA = 5L * 1024 * 1024 * 1024;
    public static final long MAX_QUOTA = 100L * 1024 * 1024 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final UserService users;
    private final UserStorageSourceMapper grants;
    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);
    @FunctionalInterface public interface Work<T> { T run() throws Throwable; }
    private record Outcome<T>(T value, Throwable failure) { }

    public boolean applies(int actor) { return users.requireLoginUser(actor).getRole() == UserRoleEnum.USER; }

    public String uploadUrl(int actor, AbstractBaseFileService<?> service, String path, String name, Long size) {
        if (!(service instanceof AbstractProxyTransferService<?> proxy))
            throw new BizException("此存储源暂不支持受容量约束的上传，请联系空间管理员使用支持服务端上传的存储源");
        validateSize(size);
        var url = java.net.URI.create(proxy.getProxyUploadUrl(path, name));
        // Relative app URL lets the client select multipart proxy upload without sending
        // its session token to a third-party origin or exposing a direct-provider credential.
        return url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
    }

    public <T> T write(int actor, AbstractBaseFileService<?> service, long increase, Work<T> work) throws Throwable {
        return write(actor, service, () -> increase, work);
    }

    public <T> T write(int actor, AbstractBaseFileService<?> service, java.util.function.LongSupplier growth, Work<T> work) throws Throwable {
        if (writing.get() || !applies(actor)) return work.run();
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setTimeout(60);
        Outcome<T> outcome = tx.execute(status -> {
            // UPDATE takes the same lock on MySQL and SQLite. Permission changes delete/update this row too.
            if (jdbc.update("UPDATE user_storage_source SET quota_revision=quota_revision+1 WHERE user_id=? AND storage_source_id=? AND enable=?",
                    actor, service.getStorageId(), true) != 1) throw new BizException("云盘授权已失效，请重新申请");
            var grant = grants.getByUserIdAndStorageId(actor, service.getStorageId());
            if (grant == null || !Boolean.TRUE.equals(grant.getEnable())
                    || !MemberStoragePaths.personal(grant.getRootPath(), actor)
                    || !Objects.equals(grant.getRootPath(), service.getCurrentUserBasePath()))
                throw new BizException("个人目录授权已变化，请刷新后重试");
            long quota = grant.getQuotaBytes() == null ? DEFAULT_QUOTA : grant.getQuotaBytes();
            if (quota < 0 || quota > MAX_QUOTA) throw new BizException("成员容量配置异常，请联系空间管理员");
            long used = measure(service, grant.getRootPath(), "/");
            long increase = growth.getAsLong();
            if (increase < 0) throw new BizException("文件大小不正确");
            if (increase > 0 && (used > quota || increase > quota - used))
                throw new BizException("存储容量不足，请清理文件或向空间管理员申请增加容量");
            writing.set(true);
            try { return new Outcome<>(work.run(), null); }
            catch (Throwable failure) { return new Outcome<T>(null, failure); }
            finally { writing.remove(); }
        });
        if (outcome == null) throw new BizException("容量校验未完成");
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.value();
    }

    public long copySize(AbstractBaseFileService<?> service, String path, String name) {
        String full = MemberStoragePaths.normalize(path + "/" + name);
        if (!(service.getParam() instanceof LocalParam)) {
            var item = service.getFileItem(full);
            if (item == null) throw new BizException("源文件不存在，无法核验容量");
            if (item.getType() == FileTypeEnum.FILE) {
                validateSize(item.getSize()); return item.getSize();
            }
        }
        return measure(service, service.getCurrentUserBasePath(), full);
    }

    /** Bound a driver stream even if a caller forged the declared byte count. */
    public static InputStream bounded(InputStream input, Long size) {
        validateSize(size);
        return new FilterInputStream(input) {
            long remaining = size;
            @Override public int read() throws IOException {
                if (remaining == 0) { if (super.read() != -1) throw new IOException("文件超过声明大小"); return -1; }
                int value = super.read(); if (value < 0) throw new EOFException("文件小于声明大小"); remaining--; return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (remaining == 0) { if (super.read() != -1) throw new IOException("文件超过声明大小"); return -1; }
                int count = in.read(bytes, offset, (int) Math.min(length, remaining));
                if (count < 0) throw new EOFException("文件小于声明大小"); remaining -= count; return count;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = 0; byte[] buffer = new byte[8192];
                while (skipped < count) { int read = read(buffer, 0, (int) Math.min(buffer.length, count-skipped)); if (read < 0) break; skipped += read; }
                return skipped;
            }
        };
    }

    static void validateSize(Long size) { if (size == null || size < 0 || size > MAX_QUOTA) throw new BizException("文件大小不正确"); }

    public long measure(AbstractBaseFileService<?> service, String userRoot, String relative) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        try {
            if (service.getParam() instanceof LocalParam local) {
                Path base = Path.of(local.getFilePath()).toAbsolutePath().normalize();
                Path root = base.resolve(MemberStoragePaths.normalize(userRoot).substring(1)).normalize();
                Path target = root.resolve(MemberStoragePaths.normalize(relative).substring(1)).normalize();
                if (!target.startsWith(base) || !target.startsWith(root)) throw new IOException();
                // Check every ancestor; Files.walk alone would follow a symlink in an ancestor.
                Path current = base.getRoot();
                for (Path part : target) { current = current.resolve(part); if (Files.isSymbolicLink(current)) throw new IOException(); }
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return 0;
                long total = 0; int count = 0;
                try (var paths = Files.walk(target)) {
                    var iterator = paths.iterator();
                    while (iterator.hasNext()) {
                        Path file = iterator.next();
                        if (++count > 10_000 || System.nanoTime() > deadline || Files.isSymbolicLink(file)) throw new IOException();
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(file));
                        else if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                    }
                }
                return total;
            }
            if (!(service instanceof DirectoryMetadataProvider provider) || !provider.supportsNativePagination())
                throw new BizException("此存储源暂不支持准确的成员容量校验，暂不开放成员写入");
            ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(relative);
            long total = 0; int count = 0; Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                String directory = queue.remove();
                if (!visited.add(directory)) throw new IOException();
                String token = null; Set<String> tokens = new HashSet<>();
                do {
                    if (++count > 10_000 || System.nanoTime() > deadline) throw new IOException();
                    var page = provider.nativeDirectoryPage(directory, userRoot, 200, token);
                    count += page.rawItemCount(); if (count > 10_000) throw new IOException();
                    for (FileItemResult file : page.files()) {
                        String name = file.getName();
                        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IOException();
                        if (file.getType() == FileTypeEnum.FOLDER) queue.add(MemberStoragePaths.normalize(directory + "/" + name));
                        else if (file.getType() == FileTypeEnum.FILE && file.getSize() != null && file.getSize() >= 0) total = Math.addExact(total, file.getSize());
                        else throw new IOException();
                    }
                    token = page.nextProviderToken();
                    if (token != null && !tokens.add(token)) throw new IOException();
                } while (token != null);
            }
            return total;
        } catch (BizException failure) { throw failure; }
        catch (Exception failure) { throw new BizException("无法准确核验目录容量，未执行写入；请联系空间管理员核查"); }
    }
}
