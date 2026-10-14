package im.tlgxy.cloud.core.util;

import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reuses only the current actor's entity read inside one read-only HTTP request.
 * This is not a permission cache: workspace, membership and role checks still run,
 * and a later request always obtains a fresh database snapshot. No Redis or static
 * user map is involved, so account changes do not wait for a cache TTL.
 */
public final class RequestActorUserSnapshot {

    private static final String ATTRIBUTE = RequestActorUserSnapshot.class.getName() + ".user";

    private RequestActorUserSnapshot() { }

    public static User load(Integer userId, Supplier<User> databaseRead) {
        HttpServletRequest request = eligibleRequest(userId);
        if (request == null) {
            return databaseRead.get();
        }
        synchronized (request) {
            Object existing = request.getAttribute(ATTRIBUTE);
            if (existing instanceof User snapshot && Objects.equals(userId, snapshot.getId())) {
                return copy(snapshot);
            }
            User user = databaseRead.get();
            // Missing, disabled, anonymous and internal-template identities are never retained.
            if (isLoginAccount(userId, user)) {
                request.setAttribute(ATTRIBUTE, copy(user));
            }
            return user;
        }
    }

    /** Discard the entity if any fresh account/workspace/membership validation fails. */
    public static void invalidate(Integer userId) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            synchronized (request) {
                Object existing = request.getAttribute(ATTRIBUTE);
                if (existing instanceof User snapshot && Objects.equals(userId, snapshot.getId())) {
                    request.removeAttribute(ATTRIBUTE);
                }
            }
        }
    }

    private static HttpServletRequest eligibleRequest(Integer userId) {
        if (userId == null || BackgroundActorContext.currentActor() != null
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return null;
        }
        try {
            // Never cache target-user CRUD reads or trust an identity supplied by the caller.
            return Objects.equals(userId, TlgxyCloudAuthUtil.getCurrentUserId()) ? request : null;
        } catch (RuntimeException missingAuthentication) {
            // Keep the existing caller's authentication/error behavior when there is no actor.
            return null;
        }
    }

    private static boolean isLoginAccount(Integer userId, User user) {
        return user != null && Objects.equals(userId, user.getId()) && Boolean.TRUE.equals(user.getEnable())
                && !Objects.equals(userId, UserConstant.ANONYMOUS_ID)
                && !Objects.equals(userId, UserConstant.NEW_USER_TEMPLATE_ID)
                && user.getRole() != null && user.getRole().isLoginRole();
    }

    private static User copy(User source) {
        User copy = new User();
        BeanUtils.copyProperties(source, copy);
        if (source.getDefaultPermissions() != null) {
            copy.setDefaultPermissions(new HashSet<>(source.getDefaultPermissions()));
        }
        if (source.getCreateTime() != null) copy.setCreateTime(new Date(source.getCreateTime().getTime()));
        if (source.getUpdateTime() != null) copy.setUpdateTime(new Date(source.getUpdateTime().getTime()));
        return copy;
    }
}
