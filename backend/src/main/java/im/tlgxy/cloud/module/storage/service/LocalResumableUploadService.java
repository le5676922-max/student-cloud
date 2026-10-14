package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadCapabilitiesRequest;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadInitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Durable, bounded local uploads. Publication is deliberately no-overwrite and never replayed once uncertain. */
@Service
@Slf4j
public class LocalResumableUploadService {
    public static final int CHUNK_SIZE = 4 * 1024 * 1024;
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long TTL_MS = Duration.ofHours(24).toMillis();
    private static final int MAX_ACTOR_SESSIONS = 8, MAX_SESSIONS = 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LocalResumableUploadAccess access;
    private final DirectoryMetadataCache directories;
    private final ResumableUploadProperties settings;
    private final Clock clock;
    private final Object[] locks = new Object[128];
    private final Object[] publicationLocks = new Object[128];
    private final Semaphore ioSlots = new Semaphore(8);
    // Process-local scan position only. Durable receipts and quota reservations remain in the database.
    private long cleanupAfterExpiry;
    private String cleanupAfterId;

    @Autowired
    public LocalResumableUploadService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                      LocalResumableUploadAccess access, DirectoryMetadataCache directories,
                                      ResumableUploadProperties settings) {
        this(jdbc, manager, access, directories, settings, Clock.systemUTC());
    }

    LocalResumableUploadService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                               LocalResumableUploadAccess access, DirectoryMetadataCache directories,
                               ResumableUploadProperties settings, Clock clock) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access;
        this.directories = directories; this.settings = settings; this.clock = clock;
        if (settings.getQuotaBytes() < 2L * CHUNK_SIZE || settings.getQuotaBytes() > 500L * 1024 * 1024 * 1024)
            throw new IllegalArgumentException("Resumable upload quota must be between 8 MiB and 500 GiB");
        Arrays.setAll(locks, ignored -> new Object());
        Arrays.setAll(publicationLocks, ignored -> new Object());
    }

    public record Capabilities(boolean enabled, int chunkSize, long maxFileSize, long expiresInSeconds, String message) { }
    public record SessionView(String id, String state, int chunkSize, long size, List<Integer> receivedChunks,
                              long expiresAt, String message) { }
    private record Upload(String id, int actor, int storage, String storageKey, String grantRoot,
                          String rootFingerprint, String stagingFingerprint, String path, String name,
                          long size, String manifest, List<String> hashes, BitSet received, String state,
                          long reservedBytes, long expiresAt) { }

    public Capabilities capabilities(int actor, ResumableUploadCapabilitiesRequest request, String password) {
        var current = access.require(actor, request.storageKey(), request.path(), request.name(), password);
        boolean enabled = settings.isEnabled() && current.local() && request.size() > 0 && request.size() <= MAX_FILE_SIZE;
        return new Capabilities(enabled, CHUNK_SIZE, MAX_FILE_SIZE, TTL_MS / 1000,
                enabled ? "本地存储支持续传；刷新后重新选择同一文件即可继续" : "此文件或存储源使用原上传方式");
    }

    public SessionView initialize(int actor, ResumableUploadInitRequest request, String password) {
        requireEnabled();
        validateManifest(request);
        var current = requireLocal(access.require(actor, request.storageKey(), request.path(), request.name(), password));
        Path staging = stagingRoot(current);
        String stagingFingerprint = stagingFingerprint(staging);
        String resumeKey = LocalResumableUploadAccess.hash(actor + "\0" + current.storageId() + "\0" + current.grantRoot()
                + "\0" + current.rootFingerprint() + "\0" + current.path() + "\0" + request.name() + "\0"
                + request.size() + "\0" + request.manifestHash());
        long now = clock.millis();
        String id = transactions.execute(status -> {
            // This single small lock row serializes only capacity reservation, never filesystem I/O.
            if (jdbc.update("UPDATE resumable_upload_quota SET revision=revision+1 WHERE id=1") != 1)
                throw new BizException("上传暂存容量锁未初始化，请完成数据库迁移");
            List<String> previous = jdbc.queryForList("SELECT id FROM resumable_upload_session WHERE actor_id=? AND resume_key=? AND expires_at>? AND state IN ('OPEN','COMPLETING','COMPLETED') ORDER BY created_at DESC LIMIT 1", String.class, actor, resumeKey, now);
            if (!previous.isEmpty()) return previous.getFirst();
            if (Files.exists(current.target(), LinkOption.NOFOLLOW_LINKS)) throw new BizException("目标存在同名文件，不会覆盖；请修改文件名后上传");
            long active = jdbc.queryForObject("SELECT COUNT(*) FROM resumable_upload_session WHERE actor_id=? AND state IN ('OPEN','COMPLETING')", Long.class, actor);
            if (active >= MAX_ACTOR_SESSIONS) throw new BizException("最多保留8个未完成续传任务，请先清理不需要的任务");
            long reserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM resumable_upload_session", Long.class);
            long rows = jdbc.queryForObject("SELECT COUNT(*) FROM resumable_upload_session WHERE state<>'DELETED'", Long.class);
            long needed = Math.multiplyExact(request.size(), 2);
            if (rows >= MAX_SESSIONS || needed > settings.getQuotaBytes() - reserved)
                throw new BizException("上传暂存容量已满，请稍后重试或清理未完成任务");
            String created = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO resumable_upload_session (id,actor_id,storage_id,storage_key,grant_root,root_fingerprint,staging_fingerprint,directory_path,file_name,total_size,manifest_hash,chunk_hashes,received_mask,resume_key,state,reserved_bytes,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)",
                    created, actor, current.storageId(), request.storageKey(), current.grantRoot(), current.rootFingerprint(),
                    stagingFingerprint, current.path(), request.name(), request.size(), request.manifestHash(),
                    String.join("\n", request.chunkHashes()), "", resumeKey, needed, now, now, now + TTL_MS);
            return created;
        });
        // A failed mkdir cannot create a valid receipt; retry init still finds the same durable session.
        synchronized (lock(id)) {
            Upload upload = owned(actor, id);
            var fresh = authorize(upload, password);
            Path freshStaging = stagingRoot(fresh);
            verifyStaging(upload, freshStaging);
            if ("OPEN".equals(upload.state())) sessionDirectory(freshStaging, id, true);
            return status(actor, id, password); // Reconcile missing disk parts before the client skips received chunks.
        }
    }

    public SessionView status(int actor, String id, String password) {
        synchronized (lock(id)) {
            Upload upload = owned(actor, id);
            var current = authorize(upload, password);
            Path staging = stagingRoot(current);
            verifyStaging(upload, staging);
            if ("OPEN".equals(upload.state()) && clock.millis() < upload.expiresAt()) {
                // Disk loss must not make the client skip a missing part after a restart.
                Path folder = sessionDirectory(staging, id, true);
                BitSet valid = (BitSet) upload.received().clone();
                for (int index = valid.nextSetBit(0); index >= 0; index = valid.nextSetBit(index + 1)) {
                    Path chunk = checkedPath(folder, chunkName(index));
                    try {
                        if (!Files.isRegularFile(chunk, LinkOption.NOFOLLOW_LINKS) || Files.size(chunk) != chunkLength(upload, index)) valid.clear(index);
                    } catch (IOException failed) { throw storageError(); }
                }
                if (!valid.equals(upload.received())) { saveMask(upload, valid); upload = owned(actor, id); }
            }
            return view(upload);
        }
    }

    /** Read no more than one part plus a single extra byte; chunked HTTP cannot bypass the size limit. */
    public SessionView receiveChunk(int actor, String id, int index, String claimedHash, InputStream body, String password) {
        if (!ioSlots.tryAcquire()) throw new BizException("上传服务繁忙，请稍后重试当前分片");
        try {
            synchronized (lock(id)) {
                Upload upload = owned(actor, id);
                var current = authorize(upload, password);
                requireOpen(upload);
                if (index < 0 || index >= upload.hashes().size() || !upload.hashes().get(index).equals(claimedHash))
                    throw new BizException("上传分片编号或摘要不匹配，请重新选择原文件");
                Path staging = stagingRoot(current); verifyStaging(upload, staging);
                Path folder = sessionDirectory(staging, id, true);
                Path chunk = checkedPath(folder, chunkName(index));
                long length = chunkLength(upload, index);
                try {
                    if (Files.exists(chunk, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(chunk, LinkOption.NOFOLLOW_LINKS)) throw storageError();
                        if (Files.size(chunk) == length && digestFile(chunk, length).equals(claimedHash)) {
                            // Even an idempotent retry must carry the exact bytes it claims to send.
                            verifyBody(body, length, claimedHash);
                            authorize(upload, password);
                            upload.received().set(index); saveMask(upload, upload.received()); return view(owned(actor, id));
                        }
                        Files.delete(chunk); // Only this owned server-created temporary part, never the target.
                    }
                    Path pending = checkedPath(folder, chunkName(index) + ".pending");
                    Files.deleteIfExists(pending);
                    privateFile(pending);
                    MessageDigest digest = sha256();
                    long copied = 0;
                    try (OutputStream output = Files.newOutputStream(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = body.read(buffer, 0, (int) Math.min(buffer.length, length - copied + 1))) != -1) {
                            copied += read;
                            if (copied > length) throw new BizException("上传分片超过约定长度");
                            digest.update(buffer, 0, read); output.write(buffer, 0, read);
                        }
                    } catch (RuntimeException | IOException failure) { Files.deleteIfExists(checkedPath(folder, pending.getFileName().toString())); throw failure; }
                    if (copied != length || !HexFormat.of().formatHex(digest.digest()).equals(claimedHash)) {
                        Files.deleteIfExists(pending); throw new BizException("上传分片长度或内容校验失败，请重试");
                    }
                    try { authorize(upload, password); } // Revocation while receiving bytes must prevent acceptance.
                    catch (RuntimeException revoked) { Files.deleteIfExists(checkedPath(folder, pending.getFileName().toString())); throw revoked; }
                    Files.move(pending, chunk);
                    upload.received().set(index); saveMask(upload, upload.received());
                    return view(owned(actor, id));
                } catch (IOException failed) { throw storageError(); }
            }
        } finally { ioSlots.release(); }
    }

    public SessionView complete(int actor, String id, String password) {
        if (!ioSlots.tryAcquire()) throw new BizException("上传服务繁忙，请稍后重试完成操作");
        try {
            synchronized (lock(id)) {
                Upload upload = owned(actor, id);
                var current = authorize(upload, password);
                Path staging = stagingRoot(current); verifyStaging(upload, staging);
                if ("COMPLETED".equals(upload.state()) || "COMPLETING".equals(upload.state())) return view(upload);
                requireOpen(upload);
                if (upload.received().cardinality() != upload.hashes().size()) throw new BizException("还有分片未上传完成，请继续上传");
                Path folder = sessionDirectory(staging, id, true);
                Path assembled = checkedPath(folder, "assembled.pending");
                try {
                    Files.deleteIfExists(assembled); privateFile(assembled);
                    List<String> actualHashes = new ArrayList<>(); long copied = 0;
                    try (OutputStream output = Files.newOutputStream(assembled, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        for (int index = 0; index < upload.hashes().size(); index++) {
                            Path chunk = checkedPath(folder, chunkName(index));
                            if (!Files.isRegularFile(chunk, LinkOption.NOFOLLOW_LINKS) || Files.size(chunk) != chunkLength(upload, index)) {
                                clearReceipt(upload, index);
                                throw new BizException("暂存分片已缺失，请重新检查续传状态");
                            }
                            MessageDigest digest = sha256();
                            try (InputStream input = Files.newInputStream(chunk, LinkOption.NOFOLLOW_LINKS)) {
                                byte[] buffer = new byte[8192]; int count;
                                long partCopied = 0, partLength = chunkLength(upload, index);
                                while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, partLength - partCopied + 1))) != -1) {
                                    partCopied += count;
                                    if (partCopied > partLength) { clearReceipt(upload, index); throw new BizException("暂存分片长度发生变化，不会写入云盘"); }
                                    output.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count;
                                }
                                if (partCopied != partLength) { clearReceipt(upload, index); throw new BizException("暂存分片已缺失，请重新检查续传状态"); }
                            }
                            String actual = HexFormat.of().formatHex(digest.digest());
                            if (!actual.equals(upload.hashes().get(index))) {
                                clearReceipt(upload, index);
                                throw new BizException("暂存分片内容校验失败，请重新选择原文件继续上传");
                            }
                            actualHashes.add(actual);
                        }
                    }
                    if (copied != upload.size() || !LocalResumableUploadAccess.hash(String.join("\n", actualHashes)).equals(upload.manifest()))
                        throw new BizException("文件完整性校验失败，不会写入云盘");
                    current = authorize(upload, password);
                    ensureParent(current);
                    synchronized (publicationLock(current.target())) {
                        current = authorize(upload, password);
                        if (Files.exists(current.target(), LinkOption.NOFOLLOW_LINKS)) throw new BizException("目标存在同名文件，不会覆盖；请清理此续传任务后更改文件名");
                        directories.invalidate();
                        Integer claimed = transactions.execute(status -> jdbc.update("UPDATE resumable_upload_session SET state='COMPLETING',updated_at=? WHERE id=? AND actor_id=? AND state='OPEN' AND expires_at>?", clock.millis(), id, actor, clock.millis()));
                        if (!Integer.valueOf(1).equals(claimed)) throw new BizException("上传状态已变化，请重新检查状态");
                        // Committed BEFORE touching the target. An interrupted/failed publication is not replayed.
                        try {
                            current = authorize(upload, password);
                            checkedPath(folder, "assembled.pending");
                            // CREATE_NEW reserves the final path atomically; plain rename can replace a
                            // competing target on Unix even when Files.move omitted REPLACE_EXISTING.
                            LocalResumablePublication.publish(assembled, current.target(), upload.size());
                            current = authorize(upload, password); // Copying large files may span a revocation.
                            if (Files.size(current.target()) != upload.size())
                                throw new IOException("Published target changed before receipt");
                            directories.invalidate(); // Also fence listings populated while publication was in progress.
                            if (jdbc.update("UPDATE resumable_upload_session SET state='COMPLETED',updated_at=? WHERE id=? AND actor_id=? AND state='COMPLETING'", clock.millis(), id, actor) != 1)
                                throw new IllegalStateException("Publication receipt unavailable");
                        } catch (RuntimeException | IOException uncertain) {
                            log.warn("Resumable publication needs manual verification ({})", uncertain.getClass().getSimpleName());
                            throw new BizException("完成结果待核查，请检查目标目录；系统不会自动重复写入或覆盖文件");
                        }
                    }
                    try { removeTemporaryFiles(upload, staging); releaseReservation(id, "COMPLETED"); }
                    catch (RuntimeException ignored) { log.warn("Completed upload temporary cleanup deferred to expiry"); }
                    return view(owned(actor, id));
                } catch (IOException failed) { throw storageError(); }
            }
        } finally { ioSlots.release(); }
    }

    public void delete(int actor, String id, String password) {
        synchronized (lock(id)) {
            Upload upload = owned(actor, id);
            var current = authorize(upload, password);
            Path staging = stagingRoot(current); verifyStaging(upload, staging);
            removeTemporaryFiles(upload, staging);
            releaseReservation(id, "DELETED"); // Never remove or change a published cloud file.
        }
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 900_000)
    public synchronized void cleanupExpired() {
        List<Map<String, Object>> expired = cleanupAfterId == null
                ? jdbc.queryForList("SELECT id,expires_at FROM resumable_upload_session WHERE expires_at<=? AND state<>'DELETED' ORDER BY expires_at,id LIMIT 32", clock.millis())
                : jdbc.queryForList("SELECT id,expires_at FROM resumable_upload_session WHERE expires_at<=? AND state<>'DELETED' AND (expires_at>? OR (expires_at=? AND id>?)) ORDER BY expires_at,id LIMIT 32", clock.millis(), cleanupAfterExpiry, cleanupAfterExpiry, cleanupAfterId);
        if (expired.isEmpty()) {
            cleanupAfterId = null; cleanupAfterExpiry = 0; // The next bounded pass wraps and retries deferred rows.
        } else {
            Map<String, Object> last = expired.getLast();
            cleanupAfterId = text(last, "id"); cleanupAfterExpiry = number(last, "expires_at").longValue();
        }
        for (var candidate : expired) {
            String id = text(candidate, "id");
            try {
                synchronized (lock(id)) {
                    Upload upload = load(id);
                    if (upload.expiresAt() > clock.millis() || "DELETED".equals(upload.state())) continue;
                    Path root = existingStagingRoot(); verifyStaging(upload, root);
                    removeTemporaryFiles(upload, root); releaseReservation(id, "DELETED");
                }
            } catch (RuntimeException failure) { log.warn("Expired upload cleanup deferred ({})", failure.getClass().getSimpleName()); }
        }
        jdbc.update("DELETE FROM resumable_upload_session WHERE state='DELETED' AND updated_at<?", clock.millis() - TTL_MS);
    }

    private LocalResumableUploadAccess.Access authorize(Upload upload, String password) {
        var current = requireLocal(access.require(upload.actor(), upload.storageKey(), upload.path(), upload.name(), password));
        requireBinding(upload, current); return current;
    }
    private static void requireBinding(Upload upload, LocalResumableUploadAccess.Access current) {
        if (upload.storage() != current.storageId() || !upload.grantRoot().equals(current.grantRoot())
                || !upload.rootFingerprint().equals(current.rootFingerprint()) || !upload.path().equals(current.path())
                || !upload.name().equals(current.name())) throw new BizException("存储源或授权根目录已变化，不能续传旧任务");
    }
    private static LocalResumableUploadAccess.Access requireLocal(LocalResumableUploadAccess.Access access) {
        if (!access.local()) throw new BizException("分片续传当前仅支持本地存储"); return access;
    }
    private void requireEnabled() { if (!settings.isEnabled()) throw new BizException("分片续传暂未启用"); }
    private void requireOpen(Upload upload) {
        requireEnabled();
        if (!"OPEN".equals(upload.state()) || clock.millis() >= upload.expiresAt()) throw new BizException("此续传任务不能继续写入，请检查状态或重新选择文件");
    }
    private Upload owned(int actor, String id) { Upload upload = load(id); if (upload.actor() != actor) throw inaccessible(); return upload; }
    private Upload load(String id) {
        requireId(id);
        var rows = jdbc.queryForList("SELECT * FROM resumable_upload_session WHERE id=? AND state<>'DELETED'", id);
        if (rows.size() != 1) throw inaccessible();
        Map<String, Object> row = rows.getFirst();
        List<String> hashes = List.of(text(row, "chunk_hashes").split("\n"));
        BitSet bits = BitSet.valueOf(Base64.getDecoder().decode(text(row, "received_mask")));
        if (hashes.size() > 512 || bits.length() > hashes.size()) throw storageError();
        return new Upload(id, number(row, "actor_id").intValue(), number(row, "storage_id").intValue(),
                text(row, "storage_key"), text(row, "grant_root"), text(row, "root_fingerprint"), text(row, "staging_fingerprint"),
                text(row, "directory_path"), text(row, "file_name"), number(row, "total_size").longValue(),
                text(row, "manifest_hash"), hashes, bits, text(row, "state"), number(row, "reserved_bytes").longValue(), number(row, "expires_at").longValue());
    }
    private void saveMask(Upload upload, BitSet received) {
        if (jdbc.update("UPDATE resumable_upload_session SET received_mask=?,updated_at=? WHERE id=? AND actor_id=? AND state='OPEN' AND expires_at>?", Base64.getEncoder().encodeToString(received.toByteArray()), clock.millis(), upload.id(), upload.actor(), clock.millis()) != 1)
            throw new BizException("上传状态已变化，请重新检查状态");
    }
    private void clearReceipt(Upload upload, int index) {
        BitSet corrected = (BitSet) upload.received().clone(); corrected.clear(index); saveMask(upload, corrected);
    }
    private void releaseReservation(String id, String state) {
        jdbc.update("UPDATE resumable_upload_session SET reserved_bytes=0,state=?,updated_at=? WHERE id=?", state, clock.millis(), id);
    }
    private SessionView view(Upload upload) {
        String state = clock.millis() >= upload.expiresAt() ? "EXPIRED" : upload.state();
        String message = switch (state) {
            case "COMPLETED" -> "上传已完成";
            case "COMPLETING" -> "完成结果待核查，请检查目标目录；不会自动重复写入";
            case "EXPIRED" -> "续传已过期；如曾发起完成操作，请先检查目标目录";
            default -> "分片保留24小时；刷新或断网后重新选择同一文件即可继续";
        };
        return new SessionView(upload.id(), state, CHUNK_SIZE, upload.size(), upload.received().stream().boxed().toList(), upload.expiresAt(), message);
    }
    private static void validateManifest(ResumableUploadInitRequest request) {
        if (request.size() < 1 || request.size() > MAX_FILE_SIZE || request.chunkHashes() == null
                || request.chunkHashes().size() != (request.size() + CHUNK_SIZE - 1) / CHUNK_SIZE
                || request.chunkHashes().stream().anyMatch(hash -> hash == null || !hash.matches("[0-9a-f]{64}"))
                || !LocalResumableUploadAccess.hash(String.join("\n", request.chunkHashes())).equals(request.manifestHash()))
            throw new BizException("文件分片清单不匹配，请重新选择原文件");
    }
    private static long chunkLength(Upload upload, int index) { return Math.min(CHUNK_SIZE, upload.size() - (long) index * CHUNK_SIZE); }
    private Path stagingRoot(LocalResumableUploadAccess.Access current) {
        try {
            Path root = configuredStagingRoot();
            // Reject both containment directions before creating anything or deleting temporary data.
            if (root.startsWith(current.physicalRoot()) || current.physicalRoot().startsWith(root))
                throw new BizException("上传暂存目录必须与云盘存储根目录独立，请联系管理员调整配置");
            access.requirePrivateStaging(root);
            LocalResumableUploadAccess.requireNoRedirect(root);
            privateDirectories(root);
            return root.toRealPath();
        } catch (IOException failure) { throw storageError(); }
    }
    private Path configuredStagingRoot() {
        Path root = Path.of(settings.getStagingRoot()).toAbsolutePath().normalize();
        if (root.getParent() == null || root.getNameCount() < 2) throw new BizException("上传暂存目录不能使用文件系统根目录");
        return root;
    }
    private Path existingStagingRoot() {
        try { Path root = configuredStagingRoot(); access.requirePrivateStaging(root); LocalResumableUploadAccess.requireNoRedirect(root); return root.toRealPath(); }
        catch (IOException failure) { throw storageError(); }
    }
    private static String stagingFingerprint(Path root) {
        try { return LocalResumableUploadAccess.hash(LocalResumableUploadAccess.directoryFingerprint(root)); }
        catch (IOException failure) { throw storageError(); }
    }
    private static void verifyStaging(Upload upload, Path root) {
        if (!upload.stagingFingerprint().equals(stagingFingerprint(root))) throw new BizException("上传暂存卷已变化，请联系管理员核查旧任务");
    }
    private static Path sessionDirectory(Path root, String id, boolean create) {
        requireId(id);
        Path folder = checkedPath(root, id);
        try { if (create && !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) privateDirectories(folder);
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw storageError();
            return checkedPath(root, id);
        } catch (IOException failure) { throw storageError(); }
    }
    private static void privateDirectories(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part); LocalResumableUploadAccess.requireNoRedirect(current);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (current.getFileSystem().supportedFileAttributeViews().contains("posix"))
                        Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    else Files.createDirectory(current);
                } catch (FileAlreadyExistsException concurrentCreation) {
                    // Different uploads can create the common staging root concurrently; only a real directory is acceptable.
                    LocalResumableUploadAccess.requireNoRedirect(current);
                }
            }
            LocalResumableUploadAccess.requireNoRedirect(current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw storageError();
        }
    }
    private static void privateFile(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.createFile(path, PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        else Files.createFile(path);
    }
    private static Path checkedPath(Path root, String relative) {
        try { return LocalResumableUploadAccess.confined(root, relative); }
        catch (IOException failure) { throw storageError(); }
    }
    private static void ensureParent(LocalResumableUploadAccess.Access current) throws IOException {
        Path parent = current.target().getParent();
        if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        if (!current.canCreateFolder()) throw new BizException("上传目标目录不存在，且当前账号不能新建目录");
        Path folder = current.userRoot();
        for (Path part : current.userRoot().relativize(parent)) {
            folder = checkedPath(current.userRoot(), current.userRoot().relativize(folder.resolve(part)).toString());
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(folder); }
                catch (FileAlreadyExistsException concurrentCreation) { LocalResumableUploadAccess.requireNoRedirect(folder); }
            }
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw storageError();
        }
    }
    private static void removeTemporaryFiles(Upload upload, Path root) {
        Path folder = sessionDirectory(root, upload.id(), false);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            for (int index = 0; index < upload.hashes().size(); index++) {
                Files.deleteIfExists(checkedPath(folder, chunkName(index)));
                Files.deleteIfExists(checkedPath(folder, chunkName(index) + ".pending"));
            }
            Files.deleteIfExists(checkedPath(folder, "assembled.pending"));
            Files.delete(folder); // No recursive walk, glob deletion, or access to a destination path.
        } catch (IOException failure) { throw new BizException("暂存清理尚未完成，请稍后重试或联系管理员；云盘文件不会被删除"); }
    }
    private static String digestFile(Path file, long length) throws IOException {
        MessageDigest digest = sha256();
        long copied = 0;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, length - copied + 1))) != -1) {
                copied += read;
                if (copied > length) return "";
                digest.update(buffer, 0, read);
            } }
        if (copied != length) return "";
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void verifyBody(InputStream body, long expectedLength, String expectedHash) throws IOException {
        MessageDigest digest = sha256(); long copied = 0; byte[] buffer = new byte[8192]; int read;
        while ((read = body.read(buffer, 0, (int) Math.min(buffer.length, expectedLength - copied + 1))) != -1) {
            copied += read;
            if (copied > expectedLength) throw new BizException("上传分片超过约定长度");
            digest.update(buffer, 0, read);
        }
        if (copied != expectedLength || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash))
            throw new BizException("上传分片长度或内容校验失败，请重试");
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private Object lock(String id) { requireId(id); return locks[Math.floorMod(id.hashCode(), locks.length)]; }
    private Object publicationLock(Path target) { return publicationLocks[Math.floorMod(target.hashCode(), publicationLocks.length)]; }
    private static String chunkName(int index) { return "chunk-" + index + ".part"; }
    private static void requireId(String id) { if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw inaccessible(); }
    private static String text(Map<String, Object> row, String key) { return row.get(key).toString(); }
    private static Number number(Map<String, Object> row, String key) { return (Number) row.get(key); }
    private static BizException inaccessible() { return new BizException("续传任务不存在或不可访问"); }
    private static BizException storageError() { return new BizException("上传暂存文件不可用，请检查磁盘空间或联系管理员"); }
}
