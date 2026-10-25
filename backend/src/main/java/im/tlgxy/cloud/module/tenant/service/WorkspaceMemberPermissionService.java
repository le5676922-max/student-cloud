package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.status.ForbiddenAccessException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import im.tlgxy.cloud.module.user.service.UserStoragePermissionPolicy;
import im.tlgxy.cloud.module.user.service.UserStorageSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Only a workspace administrator may delegate its current, tenant-scoped file access. */
@Service
@RequiredArgsConstructor
public class WorkspaceMemberPermissionService {
    @jakarta.annotation.Resource private MemberDirectoryProvisioner directories;
    @jakarta.annotation.Resource private org.springframework.cache.CacheManager springCaches;
    private final TenantSecurityService security;
    private final TenantMapper tenants;
    private final UserMapper users;
    private final StorageSourceMapper storages;
    private final UserStorageSourceMapper grants;
    private final WorkspaceJoinRequestMapper joins;
    private final TlgxyCloudCacheManager cache;

    public record PermissionOption(String value, String name) { }
    public record StoragePermission(Integer storageSourceId, String storageSourceName, String rootPath,
                                    boolean enable, Set<String> permissions, String allowedRootPath,
                                    Set<String> allowedPermissions, Long quotaBytes) { }
    public record MemberPermissions(Integer userId, Set<String> defaultPermissions,
                                    Set<String> allowedDefaultPermissions, List<StoragePermission> storages,
                                    List<PermissionOption> permissionOptions) { }
    public record StorageUpdate(Integer storageSourceId, String rootPath, Boolean enable, Set<String> permissions, Long quotaBytes) {
        public StorageUpdate(Integer source, String root, Boolean enabled, Set<String> permissions) {
            this(source, root, enabled, permissions, MemberStorageQuotaService.DEFAULT_QUOTA);
        }
    }
    public record UpdateRequest(Set<String> defaultPermissions, List<StorageUpdate> storages) { }
    private record Context(User operator, User target) { }
    private record Delegation(StorageSource source, String root, Set<String> permissions) { }

    public MemberPermissions get(int userId) {
        Context context = requireMember(userId);
        Map<Integer, Delegation> allowed = delegations(context.operator());
        Map<Integer, UserStorageSource> existing = new HashMap<>();
        for (UserStorageSource grant : grants.selectByUserId(userId)) existing.put(grant.getStorageSourceId(), grant);
        List<StoragePermission> rows = new ArrayList<>();
        for (var entry : allowed.entrySet()) {
            Delegation delegation = entry.getValue();
            UserStorageSource current = existing.get(entry.getKey());
            String personalRoot = MemberStoragePaths.personalRoot(delegation.root(), userId);
            rows.add(new StoragePermission(entry.getKey(), delegation.source().getName(),
                    current == null ? personalRoot : current.getRootPath(),
                    current != null && Boolean.TRUE.equals(current.getEnable()),
                    current == null ? Set.of() : copy(current.getPermissions()),
                    personalRoot, delegation.permissions(), current == null || current.getQuotaBytes() == null
                            ? MemberStorageQuotaService.DEFAULT_QUOTA : current.getQuotaBytes()));
        }
        List<PermissionOption> options = Arrays.stream(FileOperatorTypeEnum.values())
                .filter(option -> UserStoragePermissionPolicy.regularAdministratorPermissions().contains(option.getValue()))
                .map(option -> new PermissionOption(option.getValue(), option.getName())).toList();
        return new MemberPermissions(userId, copy(context.target().getDefaultPermissions()),
                permitted(context.operator().getDefaultPermissions()), List.copyOf(rows), options);
    }

    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(cacheNames = UserService.USER_CACHE_KEY, key = "#userId"),
            @CacheEvict(cacheNames = UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY, allEntries = true)
    })
    public void save(int userId, UpdateRequest request) {
        Context context = requireMember(userId); // Fresh role, tenant and membership on every write.
        if (request == null || request.defaultPermissions() == null || request.storages() == null
                || request.storages().size() > 100) throw new BizException("成员权限参数不正确");
        if (!permitted(context.operator().getDefaultPermissions()).containsAll(request.defaultPermissions())) forbidden();
        Map<Integer, Delegation> allowed = delegations(context.operator());
        Set<Integer> seen = new HashSet<>();
        List<UserStorageSource> updates = new ArrayList<>();
        // Validate the complete request before making any change.
        for (StorageUpdate item : request.storages()) {
            if (item == null || item.storageSourceId() == null || !seen.add(item.storageSourceId())
                    || item.enable() == null || item.permissions() == null) throw new BizException("存储授权参数不正确");
            Delegation delegation = allowed.get(item.storageSourceId());
            if (delegation == null || !delegation.permissions().containsAll(item.permissions())) forbidden();
            String root = root(item.rootPath());
            if (item.enable() && !within(root, MemberStoragePaths.personalRoot(delegation.root(), userId))) forbidden();
            long quota = item.quotaBytes() == null ? MemberStorageQuotaService.DEFAULT_QUOTA : item.quotaBytes();
            if (quota < 0 || quota > MemberStorageQuotaService.MAX_QUOTA) throw new BizException("成员容量需在0至100TB之间");
            UserStorageSource grant = new UserStorageSource();
            grant.setUserId(userId);
            grant.setStorageSourceId(item.storageSourceId());
            grant.setRootPath(root);
            grant.setQuotaBytes(quota);
            grant.setEnable(item.enable());
            grant.setPermissions(Set.copyOf(item.permissions()));
            updates.add(grant);
        }
        User update = new User();
        update.setId(userId);
        update.setDefaultPermissions(Set.copyOf(request.defaultPermissions()));
        users.updateById(update);
        for (UserStorageSource grant : updates) {
            if (Boolean.TRUE.equals(grant.getEnable()) && directories != null)
                directories.prepare(grant.getStorageSourceId(), userId, grant.getRootPath());
            grants.delete(new LambdaQueryWrapper<UserStorageSource>().eq(UserStorageSource::getUserId, userId)
                    .eq(UserStorageSource::getStorageSourceId, grant.getStorageSourceId()));
            grants.insert(grant);
        }
        // Sources that this operator cannot manage, or did not submit, remain untouched.
        Runnable invalidate = () -> {
            cache.clearUserEnableStorageSourceCache(userId);
            if (springCaches != null) {
                var usersCache = springCaches.getCache(UserService.USER_CACHE_KEY);
                if (usersCache != null) usersCache.evict(userId);
                var accessCache = springCaches.getCache(UserStorageSourceService.USER_STORAGE_SOURCE_CACHE_KEY);
                if (accessCache != null) accessCache.clear();
            }
        };
        // An outer cloud-approval transaction must commit before stale cache entries
        // can be evicted; otherwise another request may cache the previous grant.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { invalidate.run(); }
                    });
        } else invalidate.run();
    }

    private Context requireMember(int userId) {
        User operator = security.currentUser();
        if (operator.getRole() != UserRoleEnum.TENANT_ADMIN || operator.getTenantId() == null
                || Objects.equals(operator.getId(), userId)) forbidden();
        Tenant tenant = tenants.selectById(operator.getTenantId());
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) throw new BizException("工作空间不存在或已停用");
        User target = users.selectById(userId);
        if (target == null || userId <= 2 || target.getRole() != UserRoleEnum.USER
                || !Objects.equals(target.getTenantId(), operator.getTenantId())) forbidden();
        if (joins.selectCount(new LambdaQueryWrapper<WorkspaceJoinRequest>().eq(WorkspaceJoinRequest::getUserId, userId)
                .ne(WorkspaceJoinRequest::getStatus, "APPROVED")) > 0) throw new BizException("请先审核通过此成员的加入申请");
        return new Context(operator, target);
    }

    private Map<Integer, Delegation> delegations(User operator) {
        Map<Integer, Delegation> result = new LinkedHashMap<>();
        for (UserStorageSource grant : grants.selectByUserId(operator.getId())) {
            if (!Boolean.TRUE.equals(grant.getEnable())) continue;
            StorageSource source = storages.selectById(grant.getStorageSourceId());
            if (source == null || !Boolean.TRUE.equals(source.getEnable())
                    || !Objects.equals(source.getTenantId(), operator.getTenantId())) continue;
            Set<String> permissions = permitted(grant.getPermissions());
            if (!permissions.isEmpty()) result.put(source.getId(), new Delegation(source, root(grant.getRootPath()), permissions));
        }
        return result;
    }

    private static Set<String> permitted(Set<String> values) {
        Set<String> result = new LinkedHashSet<>(copy(values));
        result.retainAll(UserStoragePermissionPolicy.regularAdministratorPermissions());
        return Set.copyOf(result);
    }

    private static Set<String> copy(Set<String> values) { return values == null ? Set.of() : Set.copyOf(values); }

    private static String root(String path) {
        if (path == null || path.length() > 1024 || !path.startsWith("/") || path.contains("\\")
                || path.contains("%") || path.chars().anyMatch(Character::isISOControl)) throw new BizException("授权根目录不正确");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) throw new BizException("授权根目录不能包含相对路径");
            if (!part.isEmpty()) parts.add(part);
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    private static boolean within(String path, String parent) {
        return parent.equals("/") || path.equals(parent) || path.startsWith(parent + "/");
    }

    private static void forbidden() { throw new ForbiddenAccessException(ErrorCode.NO_FORBIDDEN); }
}
