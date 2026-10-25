package im.tlgxy.cloud.module.tenant.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceInvitationMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStorageApprovalServiceTest {
    @TempDir Path folder;
    private JdbcTemplate jdbc;
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final WorkspaceInvitationMapper locks = mock(WorkspaceInvitationMapper.class);
    private final WorkspaceStorageProvisioningService provision = mock(WorkspaceStorageProvisioningService.class);
    private LocalStorageApprovalService service;
    private User actor;
    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + folder.resolve("approval.db")); source.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tenant(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute("CREATE TABLE `user`(id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO tenant VALUES(9,'A'),(8,'B')"); jdbc.update("INSERT INTO `user` VALUES(10),(11),(1)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-sqlite/V76__storage_local_approval.sql")).execute(source);
        service = new LocalStorageApprovalService(jdbc, security, locks, provision);
        actor = new User(); actor.setId(10); actor.setRole(UserRoleEnum.TENANT_ADMIN); actor.setTenantId(9);
        when(security.requireStorageAdministrator()).thenAnswer(call -> actor);
        when(security.currentUser()).thenAnswer(call -> actor);
        when(locks.lockEnabledTenant(9)).thenReturn(1); when(locks.lockEnabledTenant(8)).thenReturn(1);
    }
    @Test void submittingIsIdempotentByPendingStateAndCannotChooseServerPath() {
        String id = service.submit("课程共享");
        assertEquals(32, id.length()); assertEquals("PENDING", service.list().getFirst().status());
        assertThrows(BizException.class, () -> service.submit("再次申请"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_local_request", Long.class));
        verifyNoInteractions(provision);
    }
    @Test void tenantListsNeverContainOtherWorkspaces() {
        service.submit("A用途"); actor.setTenantId(8); actor.setId(11); service.submit("B用途");
        assertEquals(1, service.list().size()); assertEquals("B", service.list().getFirst().tenantName());
        actor.setRole(UserRoleEnum.ADMIN); assertEquals(2, service.list().size());
    }
    @Test void approveProvisionsControlledDirectoryOnlyOnceAndNoPasswordOrPathReturned() {
        String id = service.submit("课程资料"); actor.setRole(UserRoleEnum.ADMIN); actor.setId(1);
        when(provision.provisionExistingWorkspace(9)).thenReturn(new WorkspaceStorageProvisioningService.ProvisionedStorage(31,"workspace-9",9,"空间云盘",true));
        service.review(id, true, "同意");
        var row = service.list().getFirst(); assertEquals("APPROVED", row.status()); assertEquals(31, row.storageId());
        assertThrows(BizException.class, () -> service.review(id, true, "重复"));
        verify(provision).provisionExistingWorkspace(9);
    }
    @Test void rejectDoesNotProvisionAndAllowsNewApplicationWhileDisabledSpaceRejects() {
        String id = service.submit("第一次"); actor.setRole(UserRoleEnum.ADMIN);
        service.review(id, false, "用途不明确"); verifyNoInteractions(provision);
        actor.setRole(UserRoleEnum.TENANT_ADMIN); service.submit("补充用途");
        when(locks.lockEnabledTenant(9)).thenReturn(0);
        assertThrows(BizException.class, () -> service.submit("不可用"));
    }
    @Test void onlyGlobalAdministratorCanReviewAndInvalidOrOversizedInputRejected() {
        String id = service.submit("用途"); doThrow(new BizException("forbidden")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> service.review(id, true, "")); verifyNoInteractions(provision);
        assertThrows(BizException.class, () -> service.submit(" "));
        assertThrows(BizException.class, () -> service.submit("a".repeat(501)));
        actor.setRole(UserRoleEnum.ADMIN); assertThrows(BizException.class, () -> service.submit("admin should create directly"));
    }
}
