package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import im.tlgxy.cloud.core.cache.TlgxyCloudCacheManager;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.context.StorageSourceContext;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceAllParamDTO;
import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceStorageProvisioningServiceTest {
    @TempDir Path work;
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final WorkspaceInvitationMapper locks = mock(WorkspaceInvitationMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final WorkspaceJoinRequestMapper requests = mock(WorkspaceJoinRequestMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final StorageSourceConfigService configs = mock(StorageSourceConfigService.class);
    private final TlgxyCloudCacheManager userCache = mock(TlgxyCloudCacheManager.class);
    private final CacheManager caches = mock(CacheManager.class);
    private WorkspaceStorageProvisioningService service;
    private Path root;
    private MockedStatic<StorageSourceContext> context;

    @BeforeEach
    void setup() {
        root = work.resolve("managed");
        service = new WorkspaceStorageProvisioningService(security, tenants, locks, users, requests,
                storages, grants, configs, userCache, caches, root.toString());
        context = mockStatic(StorageSourceContext.class);
        Tenant tenant = new Tenant(); tenant.setId(10); tenant.setName("Synthetic space"); tenant.setEnable(true); tenant.setOwnerUserId(101);
        when(locks.lockEnabledTenant(10)).thenReturn(1);
        when(tenants.selectById(10)).thenReturn(tenant);
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(requests.selectList(any(Wrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> { ((StorageSource) invocation.getArgument(0)).setId(50); return 1; }).when(storages).insert(any(StorageSource.class));
        when(configs.storageSourceAllParamToConfigList(eq(50), eq(StorageTypeEnum.LOCAL), any(StorageSourceAllParamDTO.class)))
                .thenAnswer(invocation -> List.of(pathConfig(((StorageSourceAllParamDTO) invocation.getArgument(2)).getFilePath())));
    }

    @AfterEach
    void cleanupThreadState() {
        context.close();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void repairChecksSystemPermissionBeforeMapperOrFilesystemAccess() {
        doThrow(new BizException("not authorized")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(-1));
        verifyNoInteractions(tenants, locks, users, requests, storages, grants, configs, userCache, caches);
        assertFalse(Files.exists(root));
    }

    @Test
    void createsTenantOnlyDirectoryPrivateDownloadsAndLeastPrivilegeGrants() {
        User manager = user(101, 10, UserRoleEnum.TENANT_ADMIN, true, Set.of());
        User readOnly = user(102, 10, UserRoleEnum.USER, true, Set.of("preview", "download", "ignoreHidden"));
        User pending = user(103, 10, UserRoleEnum.USER, true, Set.of("upload"));
        User empty = user(104, 10, UserRoleEnum.USER, true, Set.of());
        User foreign = user(105, 20, UserRoleEnum.TENANT_ADMIN, true, Set.of("upload"));
        User global = user(1, 10, UserRoleEnum.ADMIN, true, Set.of("upload"));
        User guest = user(2, 10, UserRoleEnum.GUEST, true, Set.of("upload"));
        User disabled = user(106, 10, UserRoleEnum.USER, false, Set.of("upload"));
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(manager, readOnly, pending, empty, foreign, global, guest, disabled));
        WorkspaceJoinRequest application = new WorkspaceJoinRequest(); application.setUserId(103); application.setStatus("PENDING");
        when(requests.selectList(any(Wrapper.class))).thenReturn(List.of(application));
        var result = service.provisionNewWorkspace(10);
        assertEquals(50, result.storageId()); assertEquals("workspace-10", result.storageKey()); assertTrue(result.created());
        assertTrue(Files.isDirectory(root.resolve("tenant-10")));
        assertFalse(Files.exists(root.resolve("tenant-20")));
        var source = ArgumentCaptor.forClass(StorageSource.class);
        verify(storages).insert(source.capture());
        assertEquals(10, source.getValue().getTenantId()); assertEquals(101, source.getValue().getOwnerUserId());
        assertFalse(source.getValue().getEnableFileAnnoOperator());
        var parameter = ArgumentCaptor.forClass(StorageSourceAllParamDTO.class);
        verify(configs).storageSourceAllParamToConfigList(eq(50), eq(StorageTypeEnum.LOCAL), parameter.capture());
        assertEquals(root.resolve("tenant-10").toString(), parameter.getValue().getFilePath());
        assertTrue(parameter.getValue().isProxyPrivate()); assertEquals("", parameter.getValue().getDomain());
        var saved = ArgumentCaptor.forClass(UserStorageSource.class);
        verify(grants, times(2)).insert(saved.capture());
        assertEquals(List.of(101, 102), saved.getAllValues().stream().map(UserStorageSource::getUserId).toList());
        var managerGrant = saved.getAllValues().getFirst();
        assertTrue(managerGrant.getPermissions().containsAll(Set.of("upload", "newFolder", "rename", "delete", "move", "copy")));
        assertFalse(managerGrant.getPermissions().contains("ignorePassword"));
        assertFalse(managerGrant.getPermissions().contains("ignoreHidden"));
        assertEquals(Set.of("preview", "download"), saved.getAllValues().get(1).getPermissions());
        for (var grant : saved.getAllValues()) { assertEquals("/", grant.getRootPath()); assertEquals(50, grant.getStorageSourceId()); }
        assertEquals(UserRoleEnum.TENANT_ADMIN, manager.getRole());
        verifyNoInteractions(security);
    }

    @Test
    void repeatedRepairPreservesExistingContentDisabledGrantAndNeverDuplicatesStorageOrGrants() throws IOException {
        StorageSource existing = source(10);
        when(storages.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(configs.selectStorageConfigByStorageId(50)).thenReturn(List.of(pathConfig(root.resolve("tenant-10").toString())));
        Files.createDirectories(root.resolve("tenant-10"));
        Files.writeString(root.resolve("tenant-10/existing.txt"), "preserve synthetic contents");
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(101, 10, UserRoleEnum.TENANT_ADMIN, true, Set.of())));
        UserStorageSource disabledGrant = new UserStorageSource(); disabledGrant.setEnable(false); disabledGrant.setRootPath("/limited");
        when(grants.getByUserIdAndStorageId(101, 50)).thenReturn(disabledGrant);
        assertFalse(service.provisionExistingWorkspace(10).created());
        assertFalse(service.provisionExistingWorkspace(10).created());
        verify(storages, never()).insert(any(StorageSource.class));
        verify(grants, never()).insert(any(UserStorageSource.class));
        verify(grants, never()).updateById(any(UserStorageSource.class));
        context.verifyNoInteractions();
        assertEquals("preserve synthetic contents", Files.readString(root.resolve("tenant-10/existing.txt")));
        assertFalse(disabledGrant.getEnable()); assertEquals("/limited", disabledGrant.getRootPath());
    }

    @Test
    void keyOrPathCollisionCannotAdoptForeignOrCustomStorage() {
        StorageSource existing = source(20);
        when(storages.selectOne(any(Wrapper.class))).thenReturn(existing);
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        existing.setTenantId(10); existing.setRemark("custom source");
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        existing.setRemark("managed-workspace-shared-storage:v1"); existing.setType(StorageTypeEnum.S3);
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        existing.setType(StorageTypeEnum.LOCAL);
        when(configs.selectStorageConfigByStorageId(50)).thenReturn(List.of(pathConfig(work.toString())));
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        assertFalse(Files.exists(root));
        verify(storages, never()).insert(any(StorageSource.class));
        verifyNoInteractions(grants);
    }

    @Test
    void unknownNonemptyDirectoryIsPreservedAndDisabledTenantCannotProvision() throws IOException {
        Files.createDirectories(root.resolve("tenant-10"));
        Files.writeString(root.resolve("tenant-10/unknown.txt"), "not owned by managed storage");
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        assertTrue(Files.exists(root.resolve("tenant-10/unknown.txt")));
        when(locks.lockEnabledTenant(10)).thenReturn(0);
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(0));
        verify(storages, never()).insert(any(StorageSource.class));
        verifyNoInteractions(grants, configs);
    }

    @Test
    void symbolicLinkCannotTurnAWorkspaceDriveIntoAnotherDirectory() throws IOException {
        Files.createDirectories(root);
        Path outside = Files.createDirectory(work.resolve("other-space"));
        try { Files.createSymbolicLink(root.resolve("tenant-10"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "This platform does not permit creating test symlinks");
        }
        assertThrows(BizException.class, () -> service.provisionExistingWorkspace(10));
        verify(storages, never()).insert(any(StorageSource.class));
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void outerTransactionRollbackDestroysContextAndOnlyItsNewEmptyDirectory() {
        TransactionSynchronizationManager.initSynchronization();
        service.provisionNewWorkspace(10);
        assertTrue(Files.exists(root.resolve("tenant-10")));
        verifyNoInteractions(userCache, caches);
        for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations())
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(Files.exists(root.resolve("tenant-10")));
        assertTrue(Files.exists(root));
        context.verify(() -> StorageSourceContext.destroy(argThat(value -> value.getId() == 50 && value.getTenantId() == 10)));
    }

    @Test
    void rollbackDoesNotDeleteFilesOrPreviouslyExistingDirectory() throws IOException {
        TransactionSynchronizationManager.initSynchronization();
        service.provisionNewWorkspace(10);
        Files.writeString(root.resolve("tenant-10/concurrent.txt"), "never recursively delete");
        for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations())
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(Files.exists(root.resolve("tenant-10/concurrent.txt")));
        TransactionSynchronizationManager.clearSynchronization();
        reset(storages);
        when(storages.selectOne(any(Wrapper.class))).thenReturn(null);
        // Even after a database rollback, unknown content blocks directory adoption.
        assertThrows(BizException.class, () -> service.provisionNewWorkspace(10));
    }

    @Test
    void newlyCreatedAdministratorGetsOnlyItsManagedDriveWhileOtherRolesAndEditsRemainUntouched() {
        StorageSource existing = source(10);
        when(storages.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(configs.selectStorageConfigByStorageId(50)).thenReturn(List.of(pathConfig(root.resolve("tenant-10").toString())));
        User manager = user(101, 10, UserRoleEnum.TENANT_ADMIN, true, Set.of());
        when(users.selectById(101)).thenReturn(manager);
        service.grantToNewWorkspaceAdministrator(manager);
        verify(grants).insert(argThat(value -> value.getUserId() == 101 && value.getStorageSourceId() == 50));
        clearInvocations(grants, locks, storages);
        service.grantToNewWorkspaceAdministrator(user(102, 10, UserRoleEnum.USER, true, Set.of("upload")));
        service.grantToNewWorkspaceAdministrator(user(1, null, UserRoleEnum.ADMIN, true, Set.of("upload")));
        service.grantToNewWorkspaceAdministrator(user(103, 10, UserRoleEnum.TENANT_ADMIN, false, Set.of("upload")));
        verifyNoInteractions(grants, locks, storages);
    }

    @Test
    void repeatingProvisionDoesNotRestoreAnExplicitlyDeletedGrant() throws IOException {
        when(storages.selectOne(any(Wrapper.class))).thenReturn(source(10));
        when(configs.selectStorageConfigByStorageId(50)).thenReturn(List.of(pathConfig(root.resolve("tenant-10").toString())));
        Files.createDirectories(root.resolve("tenant-10"));
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(101, 10, UserRoleEnum.TENANT_ADMIN, true, Set.of("upload"))));
        when(grants.getByUserIdAndStorageId(101, 50)).thenReturn(null);
        assertFalse(service.provisionExistingWorkspace(10).created());
        verifyNoInteractions(grants, users, requests);
    }

    @Test
    void privateUploadStagingCannotBeProvisionedAsASharedDrive() {
        ResumableUploadProperties properties = new ResumableUploadProperties();
        properties.setStagingRoot(root.toString());
        ReflectionTestUtils.setField(service, "stagingPolicy", new LocalUploadStagingPolicy(properties));

        assertThrows(BizException.class, () -> service.provisionNewWorkspace(10));

        assertFalse(Files.exists(root));
        verify(storages, never()).insert(any(StorageSource.class));
        verifyNoInteractions(grants, configs);
    }

    @Test
    void existingManagedDriveCannotGrantAccessToPrivateUploadStaging() {
        ResumableUploadProperties properties = new ResumableUploadProperties();
        properties.setStagingRoot(root.resolve("tenant-10/private-staging").toString());
        ReflectionTestUtils.setField(service, "stagingPolicy", new LocalUploadStagingPolicy(properties));
        when(storages.selectOne(any(Wrapper.class))).thenReturn(source(10));
        when(configs.selectStorageConfigByStorageId(50)).thenReturn(List.of(pathConfig(root.resolve("tenant-10").toString())));
        User manager = user(101, 10, UserRoleEnum.TENANT_ADMIN, true, Set.of());
        when(users.selectById(101)).thenReturn(manager);

        assertThrows(BizException.class, () -> service.grantToNewWorkspaceAdministrator(manager));

        verifyNoInteractions(grants);
        assertFalse(Files.exists(root));
    }

    private static StorageSource source(int tenantId) {
        StorageSource source = new StorageSource(); source.setId(50); source.setKey("workspace-10"); source.setName("空间共享云盘");
        source.setTenantId(tenantId); source.setEnable(true); source.setType(StorageTypeEnum.LOCAL); source.setRemark("managed-workspace-shared-storage:v1");
        return source;
    }
    private static StorageSourceConfig pathConfig(String path) {
        StorageSourceConfig config = new StorageSourceConfig(); config.setName("filePath"); config.setValue(path); return config;
    }
    private static User user(int id, Integer tenantId, UserRoleEnum role, boolean enabled, Set<String> permissions) {
        User user = new User(); user.setId(id); user.setTenantId(tenantId); user.setRole(role); user.setEnable(enabled); user.setDefaultPermissions(permissions); return user;
    }
}
