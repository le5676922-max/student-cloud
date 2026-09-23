package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.filter.service.FilterConfigService;
import im.tlgxy.cloud.module.password.service.PasswordConfigService;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.impl.LocalServiceImpl;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.io.IOException;
import java.util.Objects;

/** Never follows download URLs or accepts server-side paths. Every read rechecks live grants. */
@Service
@RequiredArgsConstructor
public class LearningFileReader {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final StorageSourceService storages;
    private final UserStorageSourceService grants;
    private final FilterConfigService filters;
    private final PasswordConfigService passwords;

    public byte[] read(String storageKey, String requestedPath) {
        String path = normalizePath(requestedPath);
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        var source = storages.findAllEnableOrderByOrderNum(userId).stream()
                .filter(s -> Objects.equals(s.getKey(), storageKey)).findFirst()
                .orElseThrow(LearningFileReader::unavailable);
        Integer id = storages.findIdByKey(source.getKey());
        if (id == null) throw unavailable();
        var grant = grants.getByUserIdAndStorageId(userId, id);
        if (grant == null || !Boolean.TRUE.equals(grant.getEnable()) || grant.getPermissions() == null
                || !(grant.getPermissions().contains(FileOperatorTypeEnum.PREVIEW.getValue())
                || grant.getPermissions().contains(FileOperatorTypeEnum.DOWNLOAD.getValue()))) {
            throw unavailable();
        }
        try {
            im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService<?> driver = StorageSourceContext.getByStorageId(id);
            if (!(driver instanceof LocalServiceImpl local)) {
                throw new BizException("课程资料索引目前仅支持本地存储");
            }
            String base = grant.getRootPath() == null || grant.getRootPath().isBlank() ? "/" : grant.getRootPath();
            String relativeBase = normalizePath(base + "/_").substring(1);
            relativeBase = relativeBase.substring(0, relativeBase.length() - 1);
            String fullPath = "/" + relativeBase + path.substring(1);
            // Check every ancestor as well as the file; do not bypass hidden/password folders.
            for (String candidate : new String[]{path, fullPath}) {
                String current = candidate;
                while (!current.isEmpty()) {
                    requirePathAccess(id, current);
                    int slash = current.lastIndexOf('/');
                    if (slash < 0) break;
                    current = current.substring(0, slash);
                    requirePathAccess(id, current + "/");
                }
            }
            Path root = Path.of(local.getParam().getFilePath()).toRealPath();
            Path userRoot = confined(root, relativeBase);
            Path file = confined(userRoot, path.substring(1));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                throw new BizException("资料必须为不超过 5 MB 的普通文件");
            }
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new BizException("资料不能超过 5 MB");
                return bytes;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private void requirePathAccess(int id, String path) {
        if (filters.checkFileIsHidden(id, path) || filters.checkFileIsInaccessible(id, path)
                || filters.checkFileIsDisableDownload(id, path)
                || !passwords.verifyPassword(id, path, null).isPassed()) throw unavailable();
    }

    static Path confined(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw unavailable();
        Path current = root;
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw unavailable();
        }
        Path real = target.toRealPath();
        if (!real.startsWith(root)) throw unavailable();
        return real;
    }

    public static String normalizePath(String value) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(c -> c < 32) || value.contains(":") || value.contains("%")) {
            throw new BizException("资料路径不合法");
        }
        String path = value.replace('\\', '/');
        for (String part : path.split("/")) {
            if (part.equals("..") || part.equals(".")) throw new BizException("资料路径不能包含目录跳转");
        }
        path = "/" + path.replaceAll("^/+", "").replaceAll("/+", "/");
        if (path.endsWith("/")) throw new BizException("请选择具体资料文件");
        return path;
    }

    private static BizException unavailable() {
        return new BizException("资料不存在、受保护或当前账号无权读取");
    }
}
