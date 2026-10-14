package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.util.OnlyOfficeKeyCacheUtils;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import im.tlgxy.cloud.module.ai.model.automation.Automation;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Local-only, bounded, no overwrite. Resolves current grants at both preview and execution time. */
@Service
@RequiredArgsConstructor
public class AutomationFileService {
    private final StorageSourceService storages;
    private final UserStorageSourceService grants;
    private final FilterConfigService filters;
    private final PasswordConfigService passwords;

    public record Entry(String path, long size, long modifiedAt, String locationToken) { }
    public record DuplicateGroup(long size, List<String> paths) { }
    public record DuplicateReport(int checkedFiles, int skippedFiles, List<DuplicateGroup> groups) { }

    /** Exact SHA-256 comparison, read-only and bounded to avoid exhausting storage bandwidth. */
    public DuplicateReport duplicates(String key, String directory) {
        var entries = list(key, directory, false);
        Map<Long, Long> sizes = new HashMap<>();
        entries.forEach(entry -> sizes.merge(entry.size(), 1L, Long::sum));
        Map<String, List<Entry>> hashes = new LinkedHashMap<>();
        int checked = 0;
        int skipped = 0;
        long bytes = 0;
        for (Entry entry : entries) {
            if (sizes.get(entry.size()) < 2) continue;
            if (checked >= 60 || entry.size() > 20L * 1024 * 1024 || bytes + entry.size() > 100L * 1024 * 1024) {
                skipped++;
                continue;
            }
            try {
                verify(new Automation.Step("INDEX_MATERIAL", key, entry.path(), "", null, "", entry.size(), entry.modifiedAt(), entry.locationToken()));
                Path source = resolve(key, entry.path(), Operation.READ);
                String hash = hashFile(source);
                verify(new Automation.Step("INDEX_MATERIAL", key, entry.path(), "", null, "", entry.size(), entry.modifiedAt(), entry.locationToken()));
                hashes.computeIfAbsent(entry.size() + ":" + hash, ignored -> new ArrayList<>()).add(entry);
                checked++;
                bytes += entry.size();
            } catch (BizException | IOException e) { skipped++; }
        }
        List<DuplicateGroup> groups = hashes.values().stream().filter(group -> group.size() > 1)
                .map(group -> new DuplicateGroup(group.getFirst().size(), group.stream().map(Entry::path).toList()))
                .toList();
        return new DuplicateReport(checked, skipped, groups);
    }

    private static String hashFile(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public List<Entry> list(String key, String directory, boolean moving) {
        return listEntries(key, directory, moving ? Operation.MOVE : Operation.READ);
    }

    public List<Entry> listForRename(String key, String directory) {
        return listEntries(key, directory, Operation.RENAME);
    }

    private List<Entry> listEntries(String key, String directory, Operation operation) {
        String normalized = path(directory);
        Path folder = resolve(key, normalized, operation);
        List<Entry> files = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(folder)) {
            int scanned = 0;
            for (Path file : stream) {
                if (++scanned > 400) break;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = (normalized.equals("/") ? "" : normalized) + "/" + file.getFileName();
                try {
                    Path allowed = resolve(key, relative, operation);
                    BasicFileAttributes attributes = Files.readAttributes(allowed, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    files.add(new Entry(relative, attributes.size(), attributes.lastModifiedTime().toMillis(), locationToken(allowed, attributes)));
                } catch (BizException ignored) { /* Never include protected files in a plan. */ }
            }
        } catch (IOException e) { throw new BizException("无法读取本地目录，请检查目录是否存在及文件系统权限"); }
        files.sort(Comparator.comparing(Entry::path));
        return files;
    }

    public void verify(Automation.Step step) {
        Path source = resolve(step.storageKey(), step.sourcePath(),
                step.action().equals("MOVE_FILE") ? Operation.MOVE : step.action().equals("RENAME_FILE") ? Operation.RENAME : Operation.READ);
        try {
            var attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() != step.size() || attrs.lastModifiedTime().toMillis() != step.modifiedAt()
                    || !locationToken(source, attrs).equals(step.locationToken())) {
                throw new BizException("源文件已变化，请重新生成任务");
            }
        } catch (IOException e) { throw new BizException("源文件已失效，请重新生成任务"); }
    }

    public void move(Automation.Step step) {
        verify(step);
        Path source = resolve(step.storageKey(), step.sourcePath(), true);
        Path target = resolve(step.storageKey(), step.targetPath(), true);
        var grant = grants.getByUserIdAndStorageId(TlgxyCloudAuthUtil.getCurrentUserId(), storages.findIdByKey(step.storageKey()));
        if (grant == null) throw denied();
        String root = grant.getRootPath() == null || grant.getRootPath().isBlank() ? "/" : grant.getRootPath();
        // Invalidate before the filesystem write: a cache failure must never report a completed move as failed.
        OnlyOfficeKeyCacheUtils.removeByFile(new OnlyOfficeFile(step.storageKey(), path(root + "/" + step.sourcePath())));
        try {
            if (!Files.exists(target.getParent(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target.getParent());
            // Resolve again after creating the one category directory, rejecting symlink substitutions.
            target = resolve(step.storageKey(), step.targetPath(), true);
            source = resolve(step.storageKey(), step.sourcePath(), true);
            Files.move(source, target); // Deliberately no REPLACE_EXISTING / ATOMIC_MOVE (provider may replace).
        } catch (FileAlreadyExistsException e) { throw new BizException("目标存在同名文件，已跳过，不会覆盖"); }
        catch (IOException e) { throw new BizException("移动失败，请检查源文件、目标目录或文件系统权限"); }
    }

    public void checkTarget(String key, String target) {
        if (Files.exists(resolve(key, target, true), LinkOption.NOFOLLOW_LINKS)) throw new BizException("目标存在同名文件，无法生成无覆盖整理任务");
    }

    public void checkRenameTarget(String key, String target) {
        if (Files.exists(resolve(key, target, Operation.RENAME), LinkOption.NOFOLLOW_LINKS))
            throw new BizException("目标存在同名文件，无法生成无覆盖重命名任务");
    }

    public void rename(Automation.Step step) {
        verify(step);
        Path source = resolve(step.storageKey(), step.sourcePath(), Operation.RENAME);
        Path target = resolve(step.storageKey(), step.targetPath(), Operation.RENAME);
        if (!source.getParent().equals(target.getParent())) throw new BizException("重命名不能移动文件到其他目录");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new BizException("目标存在同名文件，已跳过，不会覆盖");
        var grant = grants.getByUserIdAndStorageId(TlgxyCloudAuthUtil.getCurrentUserId(), storages.findIdByKey(step.storageKey()));
        if (grant == null) throw denied();
        String root = grant.getRootPath() == null || grant.getRootPath().isBlank() ? "/" : grant.getRootPath();
        // Match ordinary moves: invalidate the old editor key before changing its path.
        OnlyOfficeKeyCacheUtils.removeByFile(new OnlyOfficeFile(step.storageKey(), path(root + "/" + step.sourcePath())));
        try {
            // Re-resolve after cache invalidation in case the grant or path changed meanwhile.
            source = resolve(step.storageKey(), step.sourcePath(), Operation.RENAME);
            target = resolve(step.storageKey(), step.targetPath(), Operation.RENAME);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new BizException("目标存在同名文件，已跳过，不会覆盖");
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) { throw new BizException("目标存在同名文件，已跳过，不会覆盖"); }
        catch (IOException e) { throw new BizException("重命名失败，请检查文件权限与存储状态"); }
    }

    private Path resolve(String key, String requested, boolean moving) {
        return resolve(key, requested, moving ? Operation.MOVE : Operation.READ);
    }

    private enum Operation { READ, MOVE, RENAME }

    private Path resolve(String key, String requested, Operation operation) {
        String relative = path(requested);
        Integer user = TlgxyCloudAuthUtil.getCurrentUserId();
        if (storages.findAllEnableOrderByOrderNum(user).stream().noneMatch(s -> Objects.equals(s.getKey(), key))) throw denied();
        Integer id = storages.findIdByKey(key);
        var grant = grants.getByUserIdAndStorageId(user, id);
        if (grant == null || !Boolean.TRUE.equals(grant.getEnable()) || grant.getPermissions() == null) throw denied();
        var permissions = grant.getPermissions();
        if (operation == Operation.MOVE) {
            if (!permissions.contains(FileOperatorTypeEnum.MOVE.getValue()) || !permissions.contains(FileOperatorTypeEnum.NEW_FOLDER.getValue())) throw denied();
        } else if (operation == Operation.RENAME) {
            if (!permissions.contains(FileOperatorTypeEnum.RENAME.getValue())) throw denied();
        } else if (!permissions.contains("preview") && !permissions.contains("download")) throw denied();
        String userBase = path(grant.getRootPath() == null || grant.getRootPath().isBlank() ? "/" : grant.getRootPath());
        String full = path(userBase + "/" + relative);
        Set<String> checked = new HashSet<>();
        for (String initial : List.of(relative, full)) {
            String current = initial;
            while (true) {
                for (String candidate : List.of(current, current.equals("/") ? "/" : current + "/")) {
                    if (checked.add(candidate) && (filters.checkFileIsHidden(id, candidate)
                            || filters.checkFileIsInaccessible(id, candidate)
                            || filters.checkFileIsDisableDownload(id, candidate)
                            || !passwords.verifyPassword(id, candidate, null).isPassed())) throw denied();
                }
                if (current.equals("/")) break;
                int slash = current.lastIndexOf('/');
                current = slash <= 0 ? "/" : current.substring(0, slash);
            }
        }
        try {
            im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService<?> driver = StorageSourceContext.getByStorageId(id);
            if (!(driver instanceof LocalServiceImpl local)) throw new BizException("自动整理和批量归档目前仅支持本地存储");
            Path root = Path.of(local.getParam().getFilePath()).toRealPath();
            Path userRoot = confined(root, userBase.substring(1)).toRealPath();
            return confined(userRoot, relative.substring(1));
        } catch (IOException e) { throw denied(); }
    }

    static Path confined(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw denied();
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw denied();
            // Windows junctions need not report isSymbolicLink. Reject any redirected component,
            // including links to a protected sibling still inside the same storage root.
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(current)) throw denied();
        }
        return target;
    }

    static String path(String value) {
        if (value == null || value.isBlank() || value.length() > 500 || value.contains(":") || value.contains("%")
                || value.chars().anyMatch(c -> c < 32)) throw new BizException("路径不合法");
        String normalized = value.replace('\\', '/');
        for (String part : normalized.split("/")) if (part.equals(".") || part.equals("..")) throw new BizException("路径不能包含目录跳转");
        normalized = "/" + normalized.replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        return normalized;
    }

    private static String locationToken(Path path, BasicFileAttributes attrs) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (path.toAbsolutePath() + "\n" + attrs.fileKey()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static BizException denied() { return new BizException("文件或目录受保护、已失效，或当前账号无操作权限"); }
}
