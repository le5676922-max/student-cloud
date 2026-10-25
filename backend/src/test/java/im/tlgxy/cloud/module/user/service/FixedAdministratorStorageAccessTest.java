package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedAdministratorStorageAccessTest {
    private final UserMapper users = mock(UserMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final StorageSourceMapper sources = mock(StorageSourceMapper.class);

    @Test void administratorDoesNotDependOnAMissingDisabledOrRestrictedStoredGrant() {
        User admin = actor(UserRoleEnum.ADMIN, true);
        when(users.selectById(10)).thenReturn(admin);
        when(sources.selectById(30)).thenReturn(source(true));
        var service = service();
        var actual = service.getByUserIdAndStorageId(10, 30);
        assertEquals("/", actual.getRootPath());
        assertTrue(actual.getEnable());
        assertTrue(actual.getPermissions().containsAll(Set.of("upload", "delete", "download", "createShareLink")));
        assertFalse(actual.getPermissions().contains("ignorePassword"));
        assertFalse(actual.getPermissions().contains("ignoreHidden"));
        verifyNoInteractions(grants);
    }

    @Test void disabledAdministratorAndDisabledOrMissingSourceRemainUnavailable() {
        User admin = actor(UserRoleEnum.ADMIN, false);
        when(users.selectById(10)).thenReturn(admin);
        when(sources.selectById(30)).thenReturn(source(true));
        var service = service();
        assertNull(service.getByUserIdAndStorageId(10, 30));
        assertTrue(service.hasFixedAdministratorRole(10), "disabled ADMIN must also bypass cached grants");
        admin.setEnable(true);
        when(sources.selectById(30)).thenReturn(source(false));
        assertNull(service.getByUserIdAndStorageId(10, 30));
        when(sources.selectById(30)).thenReturn(null);
        assertNull(service.getByUserIdAndStorageId(10, 30));
        verifyNoInteractions(grants);
    }

    @Test void membersRetainTheirExactAssignedDirectoryAndOperationSet() {
        when(users.selectById(10)).thenReturn(actor(UserRoleEnum.USER, true));
        when(sources.selectById(30)).thenReturn(source(true));
        var assigned = grant(false, "/members/u10/class-a", Set.of("preview"));
        when(grants.getByUserIdAndStorageId(10, 30)).thenReturn(assigned);
        assertSame(assigned, service().getByUserIdAndStorageId(10, 30));
        verify(sources).selectById(30);
    }

    @Test void cacheCannotReplaceFixedAuthorityAndPromotionDoesNotCacheAdministratorPrivileges() {
        User actor = actor(UserRoleEnum.USER, true);
        when(users.selectById(10)).thenAnswer(ignored -> actor);
        when(sources.selectById(30)).thenReturn(source(true));
        var assigned = grant(true, "/members/u10/class-a", Set.of("preview"));
        when(grants.getByUserIdAndStorageId(10, 30)).thenReturn(assigned);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Caching.class);
            context.registerBean("cacheManager", CacheManager.class,
                    () -> new ConcurrentMapCacheManager(UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY));
            context.registerBean("userMapper", UserMapper.class, () -> users);
            context.registerBean("userStorageSourceMapper", UserStorageSourceMapper.class, () -> grants);
            context.registerBean("storageSourceMapper", StorageSourceMapper.class, () -> sources);
            // This collaborator is not exercised; do not recursively autowire a Mockito mock.
            context.getBeanFactory().registerSingleton("userManager", mock(UserManager.class));
            context.registerBean("userStorageSourceService", UserStorageSourceService.class, UserStorageSourceService::new);
            context.refresh();
            var service = context.getBean(UserStorageSourceService.class);
            assertSame(assigned, service.getByUserIdAndStorageId(10, 30));
            assertSame(assigned, service.getByUserIdAndStorageId(10, 30));
            verify(grants, times(1)).getByUserIdAndStorageId(10, 30);
            actor.setRole(UserRoleEnum.ADMIN);
            assertEquals("/", service.getByUserIdAndStorageId(10, 30).getRootPath());
            actor.setEnable(false);
            assertNull(service.getByUserIdAndStorageId(10, 30));
            actor.setEnable(true);
            actor.setRole(UserRoleEnum.USER);
            assertSame(assigned, service.getByUserIdAndStorageId(10, 30));
            assertFalse(service.getByUserIdAndStorageId(10, 30).getPermissions().contains("delete"));
            verify(grants, times(1)).getByUserIdAndStorageId(10, 30);
        }
    }

    @Test void retiredAndMissingIdentitiesDoNotGainFixedAdministratorAuthority() {
        var service = service();
        assertNull(service.getByUserIdAndStorageId(null, 30));
        assertNull(service.getByUserIdAndStorageId(2, 30));
        assertFalse(service.hasFixedAdministratorRole(2));
        assertFalse(service.hasFixedAdministratorRole(null));
        verifyNoInteractions(users, grants, sources);
    }

    private UserStorageSourceService service() {
        var result = new UserStorageSourceService();
        ReflectionTestUtils.setField(result, "userMapper", users);
        ReflectionTestUtils.setField(result, "userStorageSourceMapper", grants);
        ReflectionTestUtils.setField(result, "storageSourceMapper", sources);
        return result;
    }

    private static User actor(UserRoleEnum role, boolean enabled) {
        var user = new User(); user.setId(10); user.setRole(role); user.setEnable(enabled); user.setTenantId(4); return user;
    }
    private static StorageSource source(boolean enabled) {
        var source = new StorageSource(); source.setId(30); source.setEnable(enabled); source.setTenantId(4); return source;
    }
    private static UserStorageSource grant(boolean enabled, String root, Set<String> permissions) {
        var grant = new UserStorageSource(); grant.setEnable(enabled); grant.setRootPath(root); grant.setPermissions(permissions); return grant;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class Caching { }
}
