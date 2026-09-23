package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
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
        User user = userMapper.selectById(TlgxyCloudAuthUtil.getCurrentUserId());
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
            throw new BizException("只有系统管理员可以执行此操作");
        }
    }

    public Integer currentManagedTenantId() {
        User user = currentUser();
        requireAdministrator(user);
        return null;
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
        requireAdministrator(operator);
        StorageSource storageSource = storageSourceMapper.selectById(storageId);
        if (storageSource == null) {
            throw new BizException("存储源不存在");
        }
        return storageSource;
    }

    private void requireAdministrator(User user) {
        if (!isSystemAdmin(user)) {
            throw new BizException("只有管理员可以执行此操作");
        }
    }
}
