package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceStorageProvisioningService;
import im.tlgxy.cloud.module.user.event.UserCopyEvent;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service/manager policy with mapper boundaries mocked; no application or database is started. */
class UserAccountPermissionPolicyTest {
    private final UserMapper users = mock(UserMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final WorkspaceJoinRequestMapper joins = mock(WorkspaceJoinRequestMapper.class);
    private final WorkspaceStorageProvisioningService workspaceStorage = mock(WorkspaceStorageProvisioningService.class);
    private final TlgxyCloudCacheManager cache = mock(TlgxyCloudCacheManager.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserManager manager = new UserManager();
    private final UserService service = new UserService();

    @BeforeAll
    static void initializeMappings() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "account-policy-test"), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "account-policy-join-test"), WorkspaceJoinRequest.class);
    }

    @BeforeEach
    void wireRealPolicy() {
        ReflectionTestUtils.setField(manager, "userMapper", users);
        ReflectionTestUtils.setField(manager, "userStorageSourceMapper", grants);
        ReflectionTestUtils.setField(manager, "workspaceStorage", workspaceStorage);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "userManager", manager);
        ReflectionTestUtils.setField(service, "storageSourceMapper", storages);
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        ReflectionTestUtils.setField(service, "tenantService", tenants);
        ReflectionTestUtils.setField(service, "workspaceJoinRequestMapper", joins);
        ReflectionTestUtils.setField(service, "studentCloudCacheManager", cache);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", events);
        when(users.selectCount(any())).thenReturn(0L);
        when(joins.selectCount(any())).thenReturn(0L);
    }

    @Test
    void globalAccountEndpointRejectsEveryCallerSuppliedPermissionPayloadBeforeAnyWrite() {
        User existing = user(1, UserRoleEnum.ADMIN, null);
        existing.setDefaultPermissions(Set.of("download"));
        when(security.requireManageUser(1)).thenReturn(existing);
        SaveUserRequest request = profile(1);
        request.setNickname("管理员新昵称");
        request.setTenantId(999);
        request.setDefaultPermissions(Set.of("ignorePassword", "unrecognized-permission"));
        UserStorageSource narrowed = access(null, 123, "/narrow", false, Set.of());
        request.setUserStorageSourceList(Arrays.asList(null, narrowed));

        assertThrows(im.tlgxy.cloud.core.exception.status.ForbiddenAccessException.class,
                () -> service.saveOrUpdate(request));
        request.setDefaultPermissions(null);
        request.setUserStorageSourceList(List.of());
        assertThrows(im.tlgxy.cloud.core.exception.status.ForbiddenAccessException.class,
                () -> service.saveOrUpdate(request));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(grants, storages, tenants, joins, workspaceStorage, cache);
        assertNull(narrowed.getUserId());
    }

    @Test
    void globalAdministratorPasswordAndProfileRemainEditableWithoutReplacingGrants() {
        when(security.requireManageUser(7)).thenReturn(user(7, UserRoleEnum.ADMIN, null));
        SaveUserRequest request = profile(7);
        request.setPassword("New-password-456");
        request.setUserStorageSourceList(null);

        User saved = service.saveOrUpdate(request);

        assertNotEquals(request.getPassword(), saved.getPassword());
        assertTrue(PasswordVerifyUtils.verify(saved.getPassword(), saved.getSalt(), request.getPassword()));
        assertEquals(regularPermissions(), saved.getDefaultPermissions());
        verify(users).updateById(saved);
        verifyNoInteractions(grants, storages);
        verify(cache).clearUserEnableStorageSourceCache(7);
    }

    @Test
    void everyGlobalAdministratorRejectsDisableOrDemotionNotOnlyTheBuiltInId() {
        for (int id : List.of(1, 7)) {
            when(security.requireManageUser(id)).thenReturn(user(id, UserRoleEnum.ADMIN, null));
            SaveUserRequest request = profile(id);
            request.setEnable(false);
            assertThrows(BizException.class, () -> service.saveOrUpdate(request));
            request.setEnable(true);
            request.setRole(UserRoleEnum.USER);
            assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        }
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(grants, storages, cache);
    }

    @Test
    void administratorManagerPathIsUpdateOnlyEvenWhenItsGrantArgumentIsNull() {
        User admin = user(1, UserRoleEnum.ADMIN, null);
        admin.setEnable(false);
        admin.setDefaultPermissions(Set.of());
        manager.saveUserInfo(admin, null);
        verify(users).updateById(admin);
        verify(users, never()).insert(any(User.class));
        verifyNoInteractions(grants, workspaceStorage);
        assertTrue(admin.getEnable());
        assertEquals(regularPermissions(), admin.getDefaultPermissions());
    }

    @Test
    void administratorManagerPathCannotBeUsedToCreateAnotherGlobalAccount() {
        User admin = user(null, UserRoleEnum.ADMIN, null);
        assertThrows(BizException.class, () -> manager.saveUserInfo(admin, List.of()));
        verify(users, never()).insert(any(User.class));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(grants, workspaceStorage);
    }

    @Test
    void globalProfileEditPreservesTheMembersDefaultsAndEveryStoredGrant() {
        User existing = user(9, UserRoleEnum.USER, 4);
        existing.setDefaultPermissions(Set.of("preview"));
        when(security.requireManageUser(9)).thenReturn(existing);
        SaveUserRequest request = profile(9);
        request.setEnable(true);

        User saved = service.saveOrUpdate(request);

        assertEquals(4, saved.getTenantId());
        assertEquals(Set.of("preview"), saved.getDefaultPermissions());
        verify(tenants).getEnabledById(4);
        verify(joins).selectCount(any());
        verify(users).updateById(saved);
        verifyNoInteractions(grants, storages, workspaceStorage);
        verify(cache).clearUserEnableStorageSourceCache(9);
    }

    @Test
    void selectedOrdinaryAccountCannotReceiveAnotherTenantsStorage() {
        when(security.requireManageUser(9)).thenReturn(user(9, UserRoleEnum.USER, 4));
        StorageSource storage = new StorageSource();
        storage.setId(30);
        storage.setTenantId(5);
        when(storages.selectById(30)).thenReturn(storage);
        SaveUserRequest request = profile(9);
        request.setUserStorageSourceList(List.of(access(null, 30, "/", true, Set.of("download"))));
        assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(grants, cache);
    }

    @Test
    void profileWithoutPermissionFieldsNeverRevokesAnOrdinaryAccountsGrants() {
        when(security.requireManageUser(9)).thenReturn(user(9, UserRoleEnum.USER, 4));
        service.saveOrUpdate(profile(9));
        verifyNoInteractions(grants);
        verify(cache).clearUserEnableStorageSourceCache(9);
    }

    @Test
    void newStorageGivesOnlyAdministratorsUsableFixedPermissionsWithoutGivingMembersManagerRoots() {
        User admin = user(1, UserRoleEnum.ADMIN, null);
        admin.setDefaultPermissions(Set.of());
        User otherAdmin = user(7, UserRoleEnum.ADMIN, null);
        otherAdmin.setDefaultPermissions(Set.of("ignorePassword", "arbitrary"));
        User member = user(9, UserRoleEnum.USER, 4);
        member.setDefaultPermissions(Set.of());
        User managerUser = user(10, UserRoleEnum.TENANT_ADMIN, 4);
        managerUser.setDefaultPermissions(Set.of("download", "ignorePassword"));
        when(users.selectList(any())).thenReturn(List.of(admin, otherAdmin, member, managerUser));

        manager.addDefaultPermissionsForTenantUsersInStorageSource(30, 4);

        ArgumentCaptor<UserStorageSource> inserted = ArgumentCaptor.forClass(UserStorageSource.class);
        verify(grants, times(3)).insert(inserted.capture());
        for (UserStorageSource grant : inserted.getAllValues()) {
            assertEquals("/", grant.getRootPath());
            assertEquals(30, grant.getStorageSourceId());
            if (grant.getUserId() == 1 || grant.getUserId() == 7) {
                assertTrue(grant.getEnable());
                assertEquals(regularPermissions(), grant.getPermissions());
            } else {
                assertTrue(grant.getEnable());
                assertEquals(10, grant.getUserId());
                assertEquals(regularPermissions(), grant.getPermissions());
            }
        }
        assertEquals(Set.of(), admin.getDefaultPermissions()); // no historical record rewrite
        assertEquals(Set.of("ignorePassword", "arbitrary"), otherAdmin.getDefaultPermissions());
    }

    @Test
    void internalTemplateIsNotExposedOrEditableThroughAccountManagement() {
        User template = user(0, UserRoleEnum.USER, 4);
        when(security.requireManageUser(0)).thenReturn(template);
        assertThrows(BizException.class, () -> service.getUserDetailById(0));
        SaveUserRequest request = profile(0);
        assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        assertThrows(BizException.class, () -> service.updateUserEnable(0, true));
        assertThrows(BizException.class, () -> service.updateUserEnable(0, false));
        verify(users, never()).updateById(any(User.class));
        verifyNoInteractions(grants, storages, cache);
    }

    @Test
    void internalTemplateRemainsAvailableForSsoRegistrationAndGetById() {
        User template = user(0, UserRoleEnum.USER, 4);
        template.setDefaultPermissions(Set.of("download"));
        when(users.selectById(0)).thenReturn(template);
        doAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(20);
            assertEquals(UserRoleEnum.USER, created.getRole());
            assertEquals(4, created.getTenantId());
            assertEquals(Set.of("download"), created.getDefaultPermissions());
            return 1;
        }).when(users).insert(any(User.class));

        assertSame(template, service.getById(0));
        assertEquals(20, service.registerFromTemplate("sso-student"));
        ArgumentCaptor<UserCopyEvent> copied = ArgumentCaptor.forClass(UserCopyEvent.class);
        verify(events).publishEvent(copied.capture());
        assertEquals(0, copied.getValue().getFromId());
        assertEquals(20, copied.getValue().getNewId());
        verifyNoInteractions(grants, security);
    }

    @Test
    void selectedAccountUpdatesKeepTheExistingPermissionCacheEvictionContracts() throws Exception {
        CacheEvict userEviction = UserService.class.getMethod("saveOrUpdate", SaveUserRequest.class).getAnnotation(CacheEvict.class);
        assertTrue(userEviction.allEntries());
        Caching permissionEviction = UserManager.class.getMethod("saveUserInfo", User.class, List.class).getAnnotation(Caching.class);
        assertEquals(1, permissionEviction.evict().length);
        assertTrue(permissionEviction.evict()[0].allEntries());
        assertArrayEquals(new String[]{UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY}, permissionEviction.evict()[0].cacheNames());
    }

    private static SaveUserRequest profile(int id) {
        SaveUserRequest request = new SaveUserRequest();
        request.setId(id);
        request.setUsername("updated-user");
        request.setNickname("Updated");
        return request;
    }

    private static User user(Integer id, UserRoleEnum role, Integer tenantId) {
        User user = new User();
        user.setId(id);
        user.setUsername("account-" + id);
        user.setRole(role);
        user.setTenantId(tenantId);
        user.setEnable(true);
        return user;
    }

    private static UserStorageSource access(Integer userId, Integer storageId, String root, boolean enabled, Set<String> permissions) {
        UserStorageSource access = new UserStorageSource();
        access.setUserId(userId);
        access.setStorageSourceId(storageId);
        access.setRootPath(root);
        access.setEnable(enabled);
        access.setPermissions(permissions);
        return access;
    }

    private static Set<String> regularPermissions() {
        return Arrays.stream(FileOperatorTypeEnum.values()).map(FileOperatorTypeEnum::getValue)
                .filter(permission -> !permission.startsWith("ignore")).collect(Collectors.toSet());
    }
}
