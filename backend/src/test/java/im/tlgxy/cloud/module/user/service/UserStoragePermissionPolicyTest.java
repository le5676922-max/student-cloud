package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UserStoragePermissionPolicyTest {
    @Test void enabledGlobalAdministratorReceivesAllOrdinaryPermissionsWithoutAStoredGrant() {
        User admin = actor(9, UserRoleEnum.ADMIN, true);
        var effective = UserStoragePermissionPolicy.effectiveGrant(admin, source(true), null);
        assertNotNull(effective);
        assertEquals(9, effective.getUserId());
        assertEquals(30, effective.getStorageSourceId());
        assertTrue(effective.getEnable());
        assertEquals("/", effective.getRootPath());
        Set<String> expected = Arrays.stream(FileOperatorTypeEnum.values()).map(FileOperatorTypeEnum::getValue)
                .filter(permission -> !permission.startsWith("ignore")).collect(Collectors.toSet());
        assertEquals(expected, effective.getPermissions());
        assertEquals(expected, UserStoragePermissionPolicy.regularAdministratorPermissions());
        assertFalse(effective.getPermissions().contains("ignorePassword"));
        assertFalse(effective.getPermissions().contains("ignoreHidden"));
    }

    @Test void legacyDisabledOrNarrowAdministratorGrantCannotReduceEffectiveAuthorityOrBeMutated() {
        var stored = grant(false, "/limited", Set.of("preview", "ignorePassword", "ignoreHidden"));
        var effective = UserStoragePermissionPolicy.effectiveGrant(actor(1, UserRoleEnum.ADMIN, true), source(true), stored);
        assertNotSame(stored, effective);
        assertTrue(effective.getEnable());
        assertEquals("/", effective.getRootPath());
        assertTrue(effective.getPermissions().contains("delete"));
        assertFalse(effective.getPermissions().contains("ignorePassword"));
        assertFalse(stored.getEnable());
        assertEquals("/limited", stored.getRootPath());
        assertEquals(Set.of("preview", "ignorePassword", "ignoreHidden"), stored.getPermissions());
    }

    @Test void nonAdministratorRolesAndMissingActorNeverAcquireInventedGrants() {
        var stored = grant(false, "/limited", Set.of("preview"));
        for (UserRoleEnum role : UserRoleEnum.values()) {
            if (role == UserRoleEnum.ADMIN) continue;
            User user = actor(9, role, true);
            if (role == UserRoleEnum.USER) assertNull(UserStoragePermissionPolicy.effectiveGrant(user, source(true), stored));
            else assertSame(stored, UserStoragePermissionPolicy.effectiveGrant(user, source(true), stored));
            assertNull(UserStoragePermissionPolicy.effectiveGrant(user, source(true), null));
        }
        assertNull(UserStoragePermissionPolicy.effectiveGrant(null, source(true), stored));
    }

    @Test void disabledMissingOrInvalidSourceCannotBeResurrectedByAnOldAdministratorGrant() {
        User admin = actor(9, UserRoleEnum.ADMIN, true);
        var stored = grant(true, "/", Set.of("download"));
        assertNull(UserStoragePermissionPolicy.effectiveGrant(admin, null, stored));
        assertNull(UserStoragePermissionPolicy.effectiveGrant(admin, source(false), stored));
        StorageSource invalid = source(true); invalid.setId(null);
        assertNull(UserStoragePermissionPolicy.effectiveGrant(admin, invalid, stored));
        invalid.setId(0);
        assertNull(UserStoragePermissionPolicy.effectiveGrant(admin, invalid, stored));
    }

    @Test void disabledTemplateAnonymousAndInvalidAdministratorIdentitiesCannotAcquireEffectiveAccess() {
        var stored = grant(true, "/", Set.of("download"));
        assertNull(UserStoragePermissionPolicy.effectiveGrant(actor(9, UserRoleEnum.ADMIN, false), source(true), stored));
        for (Integer id : new Integer[]{null, -1, 0, 2}) {
            assertNull(UserStoragePermissionPolicy.effectiveGrant(actor(id, UserRoleEnum.ADMIN, true), source(true), stored));
        }
    }

    @Test void policyNeverCachesRoleAndReturnsIndependentNonPersistentRows() {
        User actor = actor(9, UserRoleEnum.ADMIN, true);
        var first = UserStoragePermissionPolicy.effectiveGrant(actor, source(true), null);
        var second = UserStoragePermissionPolicy.effectiveGrant(actor, source(true), null);
        assertNotSame(first, second);
        assertNull(first.getId());
        assertThrows(UnsupportedOperationException.class, () -> first.getPermissions().add("ignorePassword"));
        actor.setRole(UserRoleEnum.USER);
        assertNull(UserStoragePermissionPolicy.effectiveGrant(actor, source(true), null));
    }

    private static User actor(Integer id, UserRoleEnum role, boolean enabled) {
        User actor = new User(); actor.setId(id); actor.setRole(role); actor.setEnable(enabled); return actor;
    }
    private static StorageSource source(boolean enabled) {
        StorageSource source = new StorageSource(); source.setId(30); source.setEnable(enabled); return source;
    }
    private static UserStorageSource grant(boolean enabled, String root, Set<String> permissions) {
        var grant = new UserStorageSource(); grant.setEnable(enabled); grant.setRootPath(root); grant.setPermissions(permissions); return grant;
    }
}
