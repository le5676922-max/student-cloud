package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceConfigMapper;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Re-run on every request. No password, grant decision or cloud-driver root is retained as authority. */
@Component
@RequiredArgsConstructor
public class LocalResumableUploadAccess {
    private final StorageSourceMapper sources;
    private final StorageSourceConfigMapper configurations;
    private final DirectoryAccessGuard grants;
    private final FilterConfigService filters;
    private final PasswordConfigService passwords;

    public record Access(int storageId, String storageKey, String grantRoot, String rootFingerprint,
                         String path, String name, Path physicalRoot, Path userRoot, Path target,
                         boolean local, boolean canCreateFolder) { }

    public Access require(int actor, String key, String directory, String filename, String password) {
        if (key == null || key.isBlank() || key.length() > 128) throw denied();
        String path = normalizedPath(directory);
        requireFilename(filename);
        if (password != null && password.length() > 1000) throw denied();
        var source = sources.findByStorageKey(key);
        if (source == null) throw denied();
        im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService<?> driver = StorageSourceContext.getByStorageId(source.getId());
        var grant = grants.require(actor, source.getId(), driver.getCurrentUserBasePath());
        if (!grant.permissions().contains("upload")) throw denied();
        String userBase = normalizedPath(grant.root());
        checkRules(source.getId(), path.equals("/") ? "/" + filename : path + "/" + filename,
                normalizedPath(userBase + "/" + path + "/" + filename), password);
        if (!(driver instanceof LocalServiceImpl local))
            return new Access(source.getId(), key, grant.root(), "", path, filename,
                    null, null, null, false, grant.permissions().contains("newFolder"));
        try {
            Path configured = configuredLocalRoot(source.getId());
            if (!configured.equals(Path.of(local.getParam().getFilePath()).toAbsolutePath().normalize()))
                throw new BizException("本地存储配置已变化，请刷新后重试");
            requireNoRedirect(configured);
            Path root = configured.toRealPath();
            Path userRoot = confined(root, userBase.substring(1)).toRealPath();
            if (!Files.isDirectory(userRoot, LinkOption.NOFOLLOW_LINKS)) throw denied();
            Path target = confined(userRoot, (path.equals("/") ? "" : path.substring(1) + "/") + filename);
            String fingerprint = hash(directoryFingerprint(root) + "\0" + directoryFingerprint(userRoot));
            return new Access(source.getId(), key, grant.root(), fingerprint, path, filename,
                    root, userRoot, target, true, grant.permissions().contains("newFolder"));
        } catch (IOException invalid) { throw denied(); }
    }

    /** Check every configured local root, including disabled ones, before staging writes or cleanup. */
    public void requirePrivateStaging(Path staging) {
        try {
            Path lexicalStaging = staging.toAbsolutePath().normalize();
            for (var source : sources.findByType(StorageTypeEnum.LOCAL)) {
                Path lexicalSource = configuredLocalRoot(source.getId());
                LocalUploadStagingPolicy.assertSeparated(lexicalSource, lexicalStaging);
            }
        } catch (InvalidPathException unavailable) {
            throw new BizException("无法验证暂存目录与本地存储的隔离，请联系管理员检查路径配置");
        }
    }

    private Path configuredLocalRoot(int storage) {
        var values = configurations.findByStorageIdOrderById(storage).stream()
                .filter(row -> "filePath".equals(row.getName())).map(row -> row.getValue()).toList();
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank())
            throw new BizException("本地存储路径配置不完整，无法安全启用续传");
        Path configured = Path.of(values.getFirst());
        if (!configured.isAbsolute()) throw new BizException("本地存储路径必须为绝对路径");
        return configured.normalize();
    }

    private void checkRules(int source, String relative, String full, String password) {
        Set<String> seen = new HashSet<>();
        for (String initial : List.of(relative, full)) {
            String current = initial;
            while (true) {
                for (String candidate : List.of(current, current.equals("/") ? "/" : current + "/")) {
                    if (seen.add(candidate) && (filters.checkFileIsHidden(source, candidate)
                            || filters.checkFileIsInaccessible(source, candidate)
                            || !passwords.verifyPassword(source, candidate, password).isPassed())) throw denied();
                }
                if (current.equals("/")) break;
                int slash = current.lastIndexOf('/');
                current = slash <= 0 ? "/" : current.substring(0, slash);
            }
        }
    }

    static String normalizedPath(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1000 || !raw.startsWith("/")
                || raw.contains(":") || raw.contains("%") || raw.indexOf('\\') >= 0
                || raw.chars().anyMatch(c -> c < 32)) throw new BizException("上传路径不合法");
        for (String part : raw.split("/")) if (part.equals(".") || part.equals("..")) throw denied();
        String value = raw.replaceAll("/+", "/");
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static void requireFilename(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("%")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
                || name.chars().anyMatch(c -> "<>\"|?*".indexOf(c) >= 0)
                || name.endsWith(".") || name.endsWith(" ") || name.chars().anyMatch(c -> c < 32))
            throw new BizException("上传文件名不合法");
    }

    static Path confined(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw denied();
        requireNoRedirect(target);
        return target;
    }

    static void requireNoRedirect(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current) || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current))) throw denied();
        }
    }

    static String directoryFingerprint(Path root) throws IOException {
        Path canonicalRoot = root.toRealPath();
        return directoryFingerprint(canonicalRoot,
                Files.readAttributes(canonicalRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    }

    static String directoryFingerprint(Path canonicalRoot, BasicFileAttributes attributes) {
        FileTime created = attributes.creationTime();
        if (!attributes.isDirectory() || created == null || created.compareTo(FileTime.fromMillis(0)) <= 0)
            throw new BizException("当前文件系统无法验证目录身份，不能安全启用断点续传");
        // Birth time also detects same-path reconstruction when a filesystem quickly reuses an inode.
        // OpenJDK Windows returns a null fileKey, but supplies the native creation time. Keep its
        // full precision and canonical path; neither mutable last-modified time nor epoch defaults suffice.
        Object key = attributes.fileKey();
        return canonicalRoot + "\0" + (key == null ? "creation" : key.toString()) + "@" + created;
    }

    static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BizException denied() { return new BizException("当前目录无权上传，或目录/存储源已发生变化"); }
}
