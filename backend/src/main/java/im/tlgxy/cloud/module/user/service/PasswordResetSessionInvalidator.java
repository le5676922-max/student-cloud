package im.tlgxy.cloud.module.user.service;

import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/** Runs after the password write commits: every existing session must log in again. */
@Component
@RequiredArgsConstructor
public class PasswordResetSessionInvalidator {
    private final CacheManager cacheManager;
    private final TlgxyCloudCacheManager businessCaches;

    public void invalidate(Integer userId) {
        Cache users = cacheManager.getCache(UserService.USER_CACHE_KEY);
        if (users != null) users.clear();
        businessCaches.clearUserEnableStorageSourceCache(userId);
        StpUtil.logout(userId);
    }
}
