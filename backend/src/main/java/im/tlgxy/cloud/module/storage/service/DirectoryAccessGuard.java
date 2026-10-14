package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;

/** Fresh grants are checked even when directory metadata is cached. Fail closed on stale root/rule grants. */
@Service
public class DirectoryAccessGuard {
    @Resource private UserService users;
    @Resource private StorageSourceMapper sources;
    @Resource private UserStorageSourceMapper grants;
    @Resource private UserStorageSourceService cachedGrants;

    public Grant require(int user, int source, String driverRoot) {
        users.requireLoginUser(user);
        var storage = sources.selectById(source);
        UserStorageSource current = grants.getByUserIdAndStorageId(user, source);
        if (storage == null || !Boolean.TRUE.equals(storage.getEnable())
                || current == null || !Boolean.TRUE.equals(current.getEnable())) {
            throw new BizException("当前账号无权访问此存储源");
        }
        Grant fresh = grant(current);
        UserStorageSource cached = cachedGrants.getByUserIdAndStorageId(user, source);
        if (cached == null || !Boolean.TRUE.equals(cached.getEnable()) || !fresh.equals(grant(cached))
                || !Objects.equals(fresh.root, driverRoot)) {
            throw new BizException(ErrorCode.BIZ_DIRECTORY_CHANGED);
        }
        return fresh;
    }
    private static Grant grant(UserStorageSource row) {
        return new Grant(row.getRootPath() == null || row.getRootPath().isEmpty() ? "/" : row.getRootPath(),
                row.getPermissions() == null ? Set.of() : Set.copyOf(row.getPermissions()));
    }
    public record Grant(String root, Set<String> permissions) {
        public boolean canReadFile() { return permissions.contains("download") || permissions.contains("preview"); }
    }
}
