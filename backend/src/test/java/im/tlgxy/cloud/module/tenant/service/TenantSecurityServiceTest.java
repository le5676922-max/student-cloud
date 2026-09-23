package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TenantSecurityServiceTest {

    private UserMapper userMapper;
    private StorageSourceMapper storageSourceMapper;
    private TenantSecurityService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        storageSourceMapper = mock(StorageSourceMapper.class);
        service = new TenantSecurityService(userMapper, storageSourceMapper);
    }

    @Test
    void guestCannotManageAnyUsers() {
        User operator = user(2, 10, UserRoleEnum.GUEST);
        User ownUser = user(3, 10, UserRoleEnum.USER);
        User otherTenantUser = user(4, 20, UserRoleEnum.USER);
        User otherAdmin = user(1, null, UserRoleEnum.ADMIN);

        when(userMapper.selectById(2)).thenReturn(operator);
        when(userMapper.selectById(3)).thenReturn(ownUser);
        when(userMapper.selectById(4)).thenReturn(otherTenantUser);
        when(userMapper.selectById(5)).thenReturn(otherAdmin);

        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(2);

            assertThrows(BizException.class, () -> service.requireManageUser(3));
            assertThrows(BizException.class, () -> service.requireManageUser(4));
            assertThrows(BizException.class, () -> service.requireManageUser(5));
        }
    }

    @Test
    void guestCannotManageAnyStorage() {
        User operator = user(2, 10, UserRoleEnum.GUEST);
        StorageSource ownStorage = storage(1, 10);
        StorageSource otherStorage = storage(2, 20);

        when(userMapper.selectById(2)).thenReturn(operator);
        when(storageSourceMapper.selectById(1)).thenReturn(ownStorage);
        when(storageSourceMapper.selectById(2)).thenReturn(otherStorage);

        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(2);

            assertThrows(BizException.class, () -> service.requireManageStorage(1));
            assertThrows(BizException.class, () -> service.requireManageStorage(2));
        }
    }

    @Test
    void systemAdminCanManageAllTenants() {
        User operator = user(1, null, UserRoleEnum.ADMIN);
        User target = user(4, 20, UserRoleEnum.USER);
        StorageSource storage = storage(8, 20);

        when(userMapper.selectById(1)).thenReturn(operator);
        when(userMapper.selectById(4)).thenReturn(target);
        when(storageSourceMapper.selectById(8)).thenReturn(storage);

        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(1);

            assertSame(target, service.requireManageUser(4));
            assertSame(storage, service.requireManageStorage(8));
        }
    }

    @Test
    void ordinaryUserCannotManageUsersOrStorageEvenWithinOwnTenant() {
        when(userMapper.selectById(3)).thenReturn(user(3, 10, UserRoleEnum.USER));
        try (MockedStatic<TlgxyCloudAuthUtil> auth = mockStatic(TlgxyCloudAuthUtil.class)) {
            auth.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(3);
            assertThrows(BizException.class, () -> service.requireManageUser(3));
            assertThrows(BizException.class, () -> service.requireManageStorage(1));
        }
    }

    private User user(Integer id, Integer tenantId, UserRoleEnum role) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setRole(role);
        user.setEnable(true);
        return user;
    }

    private StorageSource storage(Integer id, Integer tenantId) {
        StorageSource storageSource = new StorageSource();
        storageSource.setId(id);
        storageSource.setTenantId(tenantId);
        return storageSource;
    }
}
