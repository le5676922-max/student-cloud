package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.core.util.RequestActorUserSnapshot;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantSecurityService {

    private final UserMapper userMapper;
    private final StorageSourceMapper storageSourceMapper;

    public User currentUser() {
        Integer userId = TlgxyCloudAuthUtil.getCurrentUserId();
        User user = RequestActorUserSnapshot.load(userId, () -> userMapper.selectById(userId));
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            throw new BizException("当前账号不存在或已被停用");
        }
        return user;
    }

    public boolean isSystemAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN == user.getRole();
    }

    public void requireSystemAdmin() {
        if (!isSystemAdmin(currentUser())) {
            throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN);
        }
    }

    public Integer currentManagedTenantId() {
        User user = currentUser();
        if (user.getRole() != UserRoleEnum.TENANT_ADMIN || user.getTenantId() == null) {
            throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN);
        }
        return user.getTenantId();
    }

    public User requireManageUser(Integer userId) {
        User operator = currentUser();
        requireAdministrator(operator);
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BizException("用户不存在");
        }
        return target;
    }

    public StorageSource requireManageStorage(Integer storageId) {
        User operator = currentUser();
        requireStorageAdministrator(operator);
        StorageSource storageSource = storageSourceMapper.selectById(storageId);
        if (storageSource == null || (!isSystemAdmin(operator)
                && !java.util.Objects.equals(operator.getTenantId(), storageSource.getTenantId()))) {
            throw new BizException("存储源不存在");
        }
        return storageSource;
    }

    public User requireStorageAdministrator() {
        User actor = currentUser();
        requireStorageAdministrator(actor);
        return actor;
    }

    private void requireStorageAdministrator(User user) {
        if (!isSystemAdmin(user) && (user.getRole() != UserRoleEnum.TENANT_ADMIN || user.getTenantId() == null))
            throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN);
    }

    private void requireAdministrator(User user) {
        if (!isSystemAdmin(user)) {
            throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN);
        }
    }
}
