package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceInvitation;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.model.request.CreateWorkspaceInvitationRequest;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceEnrollmentServiceTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserStorageSourceMapper grants = mock(UserStorageSourceMapper.class);
    private final StorageSourceMapper storages = mock(StorageSourceMapper.class);
    private final UserManager manager = mock(UserManager.class);
    private final WorkspaceInvitationMapper invitations = mock(WorkspaceInvitationMapper.class);
    private final WorkspaceJoinRequestMapper requests = mock(WorkspaceJoinRequestMapper.class);
    private final WorkspaceStorageProvisioningService workspaceStorage = mock(WorkspaceStorageProvisioningService.class);
    private WorkspaceEnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceEnrollmentService(security, tenants, users, grants, storages, manager, invitations, requests, workspaceStorage);
        when(tenants.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(tenants.selectById(10)).thenReturn(tenant(10, "研究组", true));
        when(security.currentManagedTenantId()).thenReturn(10);
        when(security.currentUser()).thenReturn(user(3, 10, UserRoleEnum.TENANT_ADMIN));
        when(invitations.lockEnabledTenant(10)).thenReturn(1);
        doAnswer(invocation -> { ((User) invocation.getArgument(0)).setId(99); return null; })
                .when(manager).saveUserInfo(any(User.class), anyList());
    }

    @Test
    void newAdministratorOwnsIsolatedWorkspaceAndProvisionsItsOwnSharedDrive() {
        doAnswer(invocation -> { ((Tenant) invocation.getArgument(0)).setId(20); return 1; })
                .when(tenants).insert(any(Tenant.class));
        User applicant = new User();
        var result = service.createWorkspace(applicant, " 新工作空间 ");
        assertEquals("ACTIVE", result.status());
        assertEquals(UserRoleEnum.TENANT_ADMIN, applicant.getRole());
        assertEquals(20, applicant.getTenantId());
        assertEquals(99, result.tenant().getOwnerUserId());
        assertTrue(applicant.getEnable());
        verify(manager).saveUserInfo(eq(applicant), eq(List.of()));
        verify(workspaceStorage).provisionNewWorkspace(20);
        verifyNoInteractions(grants, storages);
    }

    @Test
    void exactNameJoinCreatesDisabledPendingApplication() {
        when(tenants.selectOne(any(Wrapper.class))).thenReturn(tenant(10, "研究组", true));
        User applicant = new User();
        var result = service.joinWorkspace(applicant, "NAME", "研究组");
        assertEquals("PENDING", result.status());
        assertFalse(applicant.getEnable());
        assertEquals(UserRoleEnum.USER, applicant.getRole());
        assertEquals(Set.of(), applicant.getDefaultPermissions());
        var application = ArgumentCaptor.forClass(WorkspaceJoinRequest.class);
        verify(requests).insert(application.capture());
        assertEquals("PENDING", application.getValue().getStatus());
        assertEquals(99, application.getValue().getUserId());
        verify(manager).saveUserInfo(eq(applicant), eq(List.of()));
        verifyNoInteractions(grants);
    }

    @Test
    void disabledWorkspaceCannotReceiveApplications() {
        when(tenants.selectOne(any(Wrapper.class))).thenReturn(tenant(10, "研究组", false));
        when(invitations.lockEnabledTenant(10)).thenReturn(0);
        assertThrows(BizException.class, () -> service.joinWorkspace(new User(), "NAME", "研究组"));
        verifyNoInteractions(manager, requests);
    }

    @Test
    void invitationCreationOnlyStoresHashAndBoundsLifetimeAndUses() {
        var result = service.createInvitation(new CreateWorkspaceInvitationRequest(7, 1));
        var saved = ArgumentCaptor.forClass(WorkspaceInvitation.class);
        verify(invitations).insert(saved.capture());
        assertTrue(result.code().matches("WI_[A-Za-z0-9_-]{43}"));
        assertEquals(64, saved.getValue().getTokenHash().length());
        assertNotEquals(result.code(), saved.getValue().getTokenHash());
        assertEquals(WorkspaceEnrollmentService.hashCode(result.code()), saved.getValue().getTokenHash());
        assertEquals(7 * 86_400_000L, saved.getValue().getExpiresAt() - saved.getValue().getCreatedAt());
        assertThrows(BizException.class, () -> service.createInvitation(new CreateWorkspaceInvitationRequest(31, 1)));
        assertThrows(BizException.class, () -> service.createInvitation(new CreateWorkspaceInvitationRequest(1, 101)));
    }

    @Test
    void invitationInheritsOnlyIssuerGrantsFromMatchingWorkspace() {
        WorkspaceInvitation invitation = invitation();
        when(invitations.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitations.lockEnabledIssuer(3, 10)).thenReturn(1);
        when(invitations.consume(eq(7), eq(10), anyLong())).thenReturn(1);
        User issuer = user(3, 10, UserRoleEnum.TENANT_ADMIN);
        issuer.setDefaultPermissions(Set.of("upload"));
        when(users.selectById(3)).thenReturn(issuer);
        when(grants.selectByUserId(3)).thenReturn(List.of(grant(5), grant(6)));
        when(storages.selectById(5)).thenReturn(storage(5, 10));
        when(storages.selectById(6)).thenReturn(storage(6, 20));
        User applicant = new User();
        var result = service.joinWorkspace(applicant, "INVITE", "WI_" + "a".repeat(43));
        var copied = ArgumentCaptor.forClass(List.class);
        verify(manager).saveUserInfo(eq(applicant), copied.capture());
        assertEquals(1, copied.getValue().size());
        assertEquals(5, ((UserStorageSource) copied.getValue().getFirst()).getStorageSourceId());
        assertEquals(Set.of("upload"), applicant.getDefaultPermissions());
        assertTrue(applicant.getEnable());
        assertEquals("ACTIVE", result.status());
    }

    @Test
    void exhaustedRevokedOrExpiredInvitationCannotCreateMember() {
        when(invitations.selectOne(any(Wrapper.class))).thenReturn(invitation());
        when(invitations.lockEnabledIssuer(3, 10)).thenReturn(1);
        when(users.selectById(3)).thenReturn(user(3, 10, UserRoleEnum.TENANT_ADMIN));
        when(invitations.consume(eq(7), eq(10), anyLong())).thenReturn(0);
        assertThrows(BizException.class, () -> service.joinWorkspace(new User(), "INVITE", "WI_" + "a".repeat(43)));
        verifyNoInteractions(manager, grants);
    }

    @Test
    void cannotReviewAnotherWorkspaceRequestOrReplayReview() {
        WorkspaceJoinRequest application = new WorkspaceJoinRequest();
        application.setId(1);
        application.setTenantId(20);
        application.setUserId(99);
        when(requests.selectById(1)).thenReturn(application);
        assertThrows(BizException.class, () -> service.review(1, true));
        verify(requests, never()).reviewPending(anyInt(), anyInt(), anyString(), anyLong(), anyInt());
        application.setTenantId(10);
        when(users.selectById(99)).thenReturn(user(99, 10, UserRoleEnum.USER));
        when(requests.reviewPending(eq(1), eq(10), eq("APPROVED"), anyLong(), eq(3))).thenReturn(0);
        assertThrows(BizException.class, () -> service.review(1, true));
        verifyNoInteractions(manager);
    }

    @Test
    void genericEnableCannotActivateUnapprovedApplication() {
        WorkspaceAdminService admin = new WorkspaceAdminService(security, tenants, users, storages, grants, manager, requests);
        when(users.selectById(99)).thenReturn(user(99, 10, UserRoleEnum.USER));
        when(requests.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(BizException.class, () -> admin.setEnabled(99, true));
        verify(users, never()).updateById(any(User.class));
    }

    private Tenant tenant(int id, String name, boolean enabled) {
        Tenant tenant = new Tenant(); tenant.setId(id); tenant.setName(name); tenant.setEnable(enabled); return tenant;
    }
    private User user(int id, int tenantId, UserRoleEnum role) {
        User user = new User(); user.setId(id); user.setTenantId(tenantId); user.setRole(role); user.setEnable(true); return user;
    }
    private WorkspaceInvitation invitation() {
        WorkspaceInvitation value = new WorkspaceInvitation(); value.setId(7); value.setTenantId(10); value.setIssuerUserId(3); return value;
    }
    private StorageSource storage(int id, int tenantId) {
        StorageSource source = new StorageSource(); source.setId(id); source.setTenantId(tenantId); return source;
    }
    private UserStorageSource grant(int storageId) {
        UserStorageSource grant = new UserStorageSource(); grant.setStorageSourceId(storageId); grant.setRootPath("/");
        grant.setEnable(true); grant.setPermissions(Set.of("upload")); return grant;
    }
}
