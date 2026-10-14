package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;
import im.tlgxy.cloud.module.storage.service.StorageSourceConfigService;
import im.tlgxy.cloud.module.storage.service.LocalUploadStagingPolicy;
import im.tlgxy.cloud.module.storage.service.ResumableUploadProperties;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provisions a workspace-shared drive, never an individual user's private home directory. */
@Service
public class WorkspaceStorageProvisioningService {
    private static final String MARKER = "managed-workspace-shared-storage:v1";
    private static final Set<String> FILE_PERMISSIONS = Set.of("available", "preview", "download", "upload",
            "newFolder", "rename", "copy", "move", "delete", "search", "batchDownload", "packageDownload",
            "copyDownloadLink", "createShareLink");
    private final TenantSecurityService security;
    private final TenantMapper tenants;
    private final WorkspaceInvitationMapper locks;
    private final UserMapper users;
    private final WorkspaceJoinRequestMapper requests;
    private final StorageSourceMapper storages;
    private final UserStorageSourceMapper grants;
    private final StorageSourceConfigService configs;
    private final TlgxyCloudCacheManager userStorageCache;
    private final CacheManager caches;
    private final Path root;
    @Autowired(required = false)
    private LocalUploadStagingPolicy stagingPolicy = new LocalUploadStagingPolicy(new ResumableUploadProperties());

    public WorkspaceStorageProvisioningService(TenantSecurityService security, TenantMapper tenants,
            WorkspaceInvitationMapper locks, UserMapper users, WorkspaceJoinRequestMapper requests,
            StorageSourceMapper storages, UserStorageSourceMapper grants, StorageSourceConfigService configs,
            TlgxyCloudCacheManager userStorageCache, CacheManager caches,
            @Value("${tongligong-student-cloud.workspace-storage.root:${tongligong-student-cloud.log.path:${user.home}/.student-cloud/logs}/../workspaces}") String root) {
        this.security = security;
        this.tenants = tenants;
        this.locks = locks;
        this.users = users;
        this.requests = requests;
        this.storages = storages;
        this.grants = grants;
        this.configs = configs;
        this.userStorageCache = userStorageCache;
        this.caches = caches;
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (this.root.getParent() == null) throw new IllegalArgumentException("共享云盘根目录不能是文件系统根目录");
    }

    public record ProvisionedStorage(Integer storageId, String storageKey, Integer tenantId, String name, boolean created) { }

    /** Explicit, idempotent repair for pre-existing spaces; no endpoint exposes the internal provisioning method. */
    @Transactional(rollbackFor = Exception.class)
    public ProvisionedStorage provisionExistingWorkspace(int tenantId) {
        security.requireSystemAdmin();
        return provision(tenantId);
    }

    /** Called only from the transactional workspace creation services, including verified public registration. */
    @Transactional(rollbackFor = Exception.class)
    public ProvisionedStorage provisionNewWorkspace(int tenantId) {
        return provision(tenantId);
    }

    /** A newly created manager can use its existing managed drive; edited permissions are never silently restored. */
    @Transactional(rollbackFor = Exception.class)
    public void grantToNewWorkspaceAdministrator(User candidate) {
        if (candidate.getRole() != UserRoleEnum.TENANT_ADMIN || !Boolean.TRUE.equals(candidate.getEnable())
                || candidate.getId() == null || candidate.getId() <= 2 || candidate.getTenantId() == null) return;
        lockTenant(candidate.getTenantId());
        User user = users.selectById(candidate.getId());
        if (user == null || user.getRole() != UserRoleEnum.TENANT_ADMIN || !Boolean.TRUE.equals(user.getEnable())
                || !Objects.equals(user.getTenantId(), candidate.getTenantId())) throw new BizException("空间管理员归属不正确");
        StorageSource source = managedSource(candidate.getTenantId());
        if (source == null || !Boolean.TRUE.equals(source.getEnable())) return;
        validateManagedSource(source, candidate.getTenantId(), directory(candidate.getTenantId()));
        grantIfAbsent(user, source);
        invalidateAfterCommit();
    }

    private ProvisionedStorage provision(int tenantId) {
        Tenant tenant = lockTenant(tenantId);
        Path directory = directory(tenantId);
        stagingPolicy.assertSeparated(directory);
        StorageSource source = managedSource(tenantId);
        boolean created = source == null;
        if (source != null) {
            validateManagedSource(source, tenantId, directory);
            if (!Boolean.TRUE.equals(source.getEnable())) throw new BizException("共享云盘已停用，请在存储源管理中启用");
        }
        boolean newDirectory;
        try {
            rejectSymlinks(root);
            Files.createDirectories(root);
            rejectSymlinks(directory);
            boolean exists = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new BizException("共享云盘目录已被占用");
            if (exists && created) {
                try (var entries = Files.list(directory)) {
                    if (entries.findAny().isPresent()) throw new BizException("共享云盘目录已有未知文件，请由系统管理员检查");
                }
            }
            newDirectory = !exists;
            if (newDirectory) Files.createDirectory(directory);
            rejectSymlinks(directory);
        } catch (IOException exception) {
            throw new BizException("共享云盘目录不可用，请检查持久化目录和写入权限");
        }
        final StorageSource createdSource = created ? new StorageSource() : null;
        // Register before inserting/configuring: any failure in the outer registration transaction must clean up.
        registerRollbackCleanup(createdSource, directory, newDirectory);
        try {
            if (created) {
                source = createdSource;
                source.setKey(key(tenantId));
                source.setName("空间共享云盘");
                source.setRemark(MARKER);
                source.setTenantId(tenantId);
                source.setOwnerUserId(tenant.getOwnerUserId());
                source.setType(StorageTypeEnum.LOCAL);
                source.setEnable(true);
                source.setEnableCache(false);
                source.setEnableFileOperator(true);
                source.setEnableFileAnnoOperator(false);
                source.setAutoRefreshCache(false);
                source.setSearchEnable(true);
                source.setSearchIgnoreCase(true);
                source.setSearchMode(SearchModeEnum.SEARCH_ALL_MODE);
                source.setOrderNum(0);
                source.setDefaultSwitchToImgMode(false);
                source.setCompatibilityReadme(false);
                storages.insert(source);
                StorageSourceAllParamDTO parameters = new StorageSourceAllParamDTO();
                parameters.setFilePath(directory.toString());
                parameters.setDomain("");
                parameters.setProxyPrivate(true);
                parameters.setProxyTokenTime(1800);
                parameters.setProxyLinkForceDownload(true);
                List<StorageSourceConfig> configuration = configs.storageSourceAllParamToConfigList(source.getId(), StorageTypeEnum.LOCAL, parameters);
                configs.saveBatch(source.getId(), configuration);
                StorageSourceContext.init(StorageSourceInitDTO.convert(source, configuration));
            }
            // A repeated request must not undo deliberate revocation (a removed grant is not distinguishable from absence).
            if (created) grantCurrentMembers(source, tenantId);
            invalidateAfterCommit();
            return new ProvisionedStorage(source.getId(), source.getKey(), tenantId, source.getName(), created);
        } catch (RuntimeException exception) {
            // Unit/direct callers have no enclosing transaction synchronization; the real application does.
            if (!TransactionSynchronizationManager.isSynchronizationActive()) cleanup(createdSource, directory, newDirectory);
            if (exception instanceof BizException) throw exception;
            throw new BizException("共享云盘开通失败，请检查存储配置与持久化目录权限");
        }
    }

    private Tenant lockTenant(int id) {
        if (id < 1 || locks.lockEnabledTenant(id) != 1) throw new BizException("工作空间不存在或已停用");
        Tenant tenant = tenants.selectById(id);
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) throw new BizException("工作空间不存在或已停用");
        return tenant;
    }

    private Path directory(int id) {
        Path result = root.resolve("tenant-" + id).normalize();
        if (!result.getParent().equals(root)) throw new BizException("共享云盘目录不正确");
        return result;
    }

    private static String key(int id) { return "workspace-" + id; }

    private StorageSource managedSource(int tenantId) {
        return storages.selectOne(Wrappers.<StorageSource>lambdaQuery().eq(StorageSource::getKey, key(tenantId)));
    }

    private void validateManagedSource(StorageSource source, int tenantId, Path directory) {
        if (!Objects.equals(source.getTenantId(), tenantId) || source.getType() != StorageTypeEnum.LOCAL
                || !MARKER.equals(source.getRemark())) throw new BizException("共享云盘标识已被其它存储源占用");
        List<StorageSourceConfig> configuration = configs.selectStorageConfigByStorageId(source.getId());
        String filePath = configuration.stream().filter(value -> "filePath".equals(value.getName()))
                .map(StorageSourceConfig::getValue).findFirst().orElse("");
        if (filePath.isBlank() || !Path.of(filePath).toAbsolutePath().normalize().equals(directory))
            throw new BizException("共享云盘目录配置已更改，请由系统管理员检查，不能自动接管其它目录");
        stagingPolicy.assertSeparated(directory);
        rejectSymlinks(directory);
    }

    private void grantCurrentMembers(StorageSource source, int tenantId) {
        List<User> members = users.selectList(Wrappers.<User>lambdaQuery().eq(User::getTenantId, tenantId)
                .gt(User::getId, 2).eq(User::getEnable, true).in(User::getRole, UserRoleEnum.TENANT_ADMIN, UserRoleEnum.USER));
        Set<Integer> unapproved = new HashSet<>();
        for (WorkspaceJoinRequest request : requests.selectList(Wrappers.<WorkspaceJoinRequest>lambdaQuery()
                .eq(WorkspaceJoinRequest::getTenantId, tenantId).ne(WorkspaceJoinRequest::getStatus, "APPROVED"))) {
            unapproved.add(request.getUserId());
        }
        for (User user : members) {
            // Recheck fixtures/invariant here, rather than trusting a malformed legacy row or mapper result.
            if (user.getId() == null || user.getId() <= 2 || !Objects.equals(user.getTenantId(), tenantId)
                    || !Boolean.TRUE.equals(user.getEnable()) || unapproved.contains(user.getId())) continue;
            if (user.getRole() == UserRoleEnum.TENANT_ADMIN || user.getRole() == UserRoleEnum.USER) grantIfAbsent(user, source);
        }
    }

    private void grantIfAbsent(User user, StorageSource source) {
        if (grants.getByUserIdAndStorageId(user.getId(), source.getId()) != null) return;
        Set<String> permissions = new HashSet<>(user.getRole() == UserRoleEnum.TENANT_ADMIN ? FILE_PERMISSIONS
                : user.getDefaultPermissions() == null ? Set.of() : user.getDefaultPermissions());
        permissions.retainAll(FILE_PERMISSIONS);
        // Old members with no default access remain ungranted; never create access from an empty permission set.
        if (permissions.isEmpty()) return;
        UserStorageSource grant = new UserStorageSource();
        grant.setUserId(user.getId());
        grant.setStorageSourceId(source.getId());
        grant.setRootPath("/"); // This '/' is the tenant-only source, NOT the server filesystem root.
        grant.setEnable(true);
        grant.setPermissions(Set.copyOf(permissions));
        grants.insert(grant);
    }

    private static void rejectSymlinks(Path directory) {
        for (Path value = directory; value != null; value = value.getParent()) {
            if (Files.isSymbolicLink(value)) throw new BizException("共享云盘目录不能包含符号链接");
        }
    }

    private static void registerRollbackCleanup(StorageSource source, Path directory, boolean newDirectory) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) cleanup(source, directory, newDirectory);
            }
        });
    }

    private static void cleanup(StorageSource source, Path directory, boolean newDirectory) {
        if (source != null && source.getId() != null) StorageSourceContext.destroy(source);
        if (!newDirectory || Files.isSymbolicLink(directory)) return;
        // No recursive deletion. A directory with even one file (including concurrent writes) is preserved.
        try { Files.deleteIfExists(directory); } catch (IOException ignored) { /* Preserve non-empty/unavailable directories. */ }
    }

    private void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { invalidateCaches(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { invalidateCaches(); }
        });
    }

    private void invalidateCaches() {
        userStorageCache.clearUserEnableStorageSourceCache();
        for (String name : List.of("storageSource", "storageSourceConfig", "userStorageSource")) {
            Cache cache = caches.getCache(name);
            if (cache != null) cache.clear();
        }
    }
}
