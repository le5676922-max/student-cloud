package im.tlgxy.cloud.module.user.service;

import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure effective-grant policy. Callers supply a freshly validated actor and source, never a session-selected role. */
public final class UserStoragePermissionPolicy {
    private UserStoragePermissionPolicy() { }

    private static final Set<String> ADMIN_PERMISSIONS = Arrays.stream(FileOperatorTypeEnum.values())
            .map(FileOperatorTypeEnum::getValue)
            // Highest ordinary file authority is not an exemption from protected-file rules.
            .filter(permission -> !permission.startsWith("ignore"))
            .collect(Collectors.toUnmodifiableSet());

    /** One immutable definition for persisted administrator defaults and effective runtime grants. */
    public static Set<String> regularAdministratorPermissions() {
        return ADMIN_PERMISSIONS;
    }

    /**
     * Existing grants remain authoritative for members and workspace managers. An enabled global
     * administrator has a fresh, non-persistent grant for an enabled source, regardless of a legacy
     * missing/disabled/restricted row. Source/workspace status and share selection remain caller checks.
     */
    public static UserStorageSource effectiveGrant(User actor, StorageSource source, UserStorageSource storedGrant) {
        if (actor == null) return null;
        if (actor.getRole() != UserRoleEnum.ADMIN) {
            if (source == null || !java.util.Objects.equals(actor.getTenantId(), source.getTenantId())) return null;
            if (actor.getRole() == UserRoleEnum.USER && storedGrant != null) {
                try {
                    if (!im.tlgxy.cloud.module.tenant.service.MemberStoragePaths.personal(storedGrant.getRootPath(), actor.getId())) return null;
                } catch (RuntimeException invalid) { return null; }
            }
            return storedGrant;
        }
        if (!Boolean.TRUE.equals(actor.getEnable()) || actor.getId() == null || actor.getId() <= 0
                || UserConstant.ANONYMOUS_ID.equals(actor.getId()) || source == null
                || source.getId() == null || source.getId() <= 0 || !Boolean.TRUE.equals(source.getEnable())) return null;
        UserStorageSource effective = new UserStorageSource();
        effective.setUserId(actor.getId());
        effective.setStorageSourceId(source.getId());
        effective.setRootPath("/");
        effective.setEnable(true);
        effective.setPermissions(ADMIN_PERMISSIONS);
        return effective;
    }
}
