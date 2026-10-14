package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Shared, read-only boundary check: private upload parts must never become a LOCAL cloud root. */
@Component
public final class LocalUploadStagingPolicy {
    private final ResumableUploadProperties properties;

    public LocalUploadStagingPolicy(ResumableUploadProperties properties) {
        this.properties = properties;
    }

    /** Applies even when resumable uploads are disabled: old private parts can still exist. */
    public void assertSeparated(String cloudRoot) {
        assertSeparated(localPath(cloudRoot));
    }

    public void assertSeparated(Path cloudRoot) {
        String configured = properties.getStagingRoot();
        if (configured == null || configured.isBlank() || configured.length() > 2048
                || isNetworkPath(configured) || configured.matches("(?s).*\\p{Cntrl}.*")) throw invalid();
        try {
            Path staging = Path.of(configured).toAbsolutePath().normalize();
            if (staging.getParent() == null || staging.getNameCount() < 2) throw invalid();
            assertSeparated(cloudRoot, staging);
        } catch (java.nio.file.InvalidPathException failure) { throw invalid(); }
    }

    /** Both lexical and physical paths are checked; the paths need not exist yet. Never creates files. */
    public static void assertSeparated(Path cloudRoot, Path stagingRoot) {
        if (cloudRoot == null || stagingRoot == null || !cloudRoot.isAbsolute() || !stagingRoot.isAbsolute())
            throw invalid();
        Path cloud = cloudRoot.toAbsolutePath().normalize();
        Path staging = stagingRoot.toAbsolutePath().normalize();
        if (!cloud.equals(cloudRoot) || !staging.equals(stagingRoot)
                || isNetworkPath(cloud.toString()) || isNetworkPath(staging.toString())) throw invalid();
        try {
            Path realCloud = canonicalExistingAncestor(cloud);
            Path realStaging = canonicalExistingAncestor(staging);
            for (Path candidate : List.of(staging, realStaging)) {
                for (Path publicRoot : List.of(cloud, realCloud)) {
                    if (candidate.startsWith(publicRoot) || publicRoot.startsWith(candidate))
                        throw new BizException("本地存储根目录不能包含上传暂存目录，也不能位于暂存目录内；请使用独立目录");
                }
            }
        } catch (IOException | SecurityException failure) { throw invalid(); }
    }

    /** Reject aliases, symlinks and Windows junctions before resolving the first existing parent. */
    private static Path canonicalExistingAncestor(Path path) throws IOException {
        Path current = path.getRoot();
        Path existing = current;
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw invalid();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                        || !current.toRealPath().equals(current)) throw invalid();
                existing = current;
            }
        }
        if (existing == null || !Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    private static Path localPath(String value) {
        if (value == null || value.isBlank() || value.length() > 2048 || isNetworkPath(value)
                || value.matches("(?s).*\\p{Cntrl}.*")) throw invalid();
        try {
            Path result = Path.of(value);
            if (!result.isAbsolute() || !result.normalize().equals(result)) throw invalid();
            return result;
        } catch (java.nio.file.InvalidPathException failure) { throw invalid(); }
    }

    private static boolean isNetworkPath(String path) { return path.startsWith("\\\\") || path.startsWith("//"); }
    private static BizException invalid() {
        return new BizException("无法验证本地存储与私有上传暂存目录的隔离；不允许相对路径、链接或网络共享，请检查服务器路径配置");
    }
}
