package im.tlgxy.cloud.module.tenant.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.storage.mapper.StorageSourceMapper;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceMemberPasswordTest {
    private final TenantSecurityService security = mock(TenantSecurityService.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final WorkspaceJoinRequestMapper joins = mock(WorkspaceJoinRequestMapper.class);
    private WorkspaceAdminService service;
    private static final String SYNTHETIC = "Synthetic-test-only-2026";

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "password"), WorkspaceJoinRequest.class);
        when(security.currentManagedTenantId()).thenReturn(10);
        Tenant tenant = new Tenant(); tenant.setId(10); tenant.setEnable(true);
        when(tenants.selectById(10)).thenReturn(tenant);
        when(joins.selectCount(any())).thenReturn(0L);
        when(users.updateById(any(User.class))).thenReturn(1);
        when(users.selectById(17)).thenReturn(member(UserRoleEnum.USER, 10));
        service = new WorkspaceAdminService(security, tenants, users, mock(StorageSourceMapper.class),
                mock(UserStorageSourceMapper.class), mock(UserManager.class), joins);
    }
    private User member(UserRoleEnum role, int tenantId) {
        User user = new User(); user.setId(17); user.setRole(role); user.setTenantId(tenantId); user.setEnable(true); return user;
    }
    @Test void resetsOnlyTheChosenMembersCredentialAndRevokesTheirSessions() {
        try (var sessions = mockStatic(StpUtil.class)) {
            service.resetMemberPassword(17, SYNTHETIC, SYNTHETIC);
            var captured = ArgumentCaptor.forClass(User.class);
            verify(users).updateById(captured.capture());
            User update = captured.getValue();
            assertEquals(17, update.getId());
            assertNotEquals(SYNTHETIC, update.getPassword());
            assertTrue(PasswordVerifyUtils.verify(update.getPassword(), update.getSalt(), SYNTHETIC));
            assertNull(update.getEnable()); assertNull(update.getRole()); assertNull(update.getTenantId());
            sessions.verify(() -> StpUtil.logout(17));
        }
    }
    @Test void refusesOtherWorkspacesAndAllAdministratorAccounts() {
        for (User invalid : new User[]{member(UserRoleEnum.USER, 20), member(UserRoleEnum.ADMIN, 10), member(UserRoleEnum.TENANT_ADMIN, 10)}) {
            when(users.selectById(17)).thenReturn(invalid);
            assertThrows(BizException.class, () -> service.resetMemberPassword(17, SYNTHETIC, SYNTHETIC));
        }
        verify(users, never()).updateById(any(User.class));
    }
    @Test void rejectsReservedMissingAndPendingAccounts() {
        for (int id : new int[]{0,1,2,99}) assertThrows(BizException.class, () -> service.resetMemberPassword(id, SYNTHETIC, SYNTHETIC));
        when(joins.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.resetMemberPassword(17, SYNTHETIC, SYNTHETIC));
        verify(users, never()).updateById(any(User.class));
    }
    @Test void validatesConfirmationAndLimitsBeforeAnyCredentialWrite() {
        for (String value : new String[]{null, "short", " ".repeat(10), "x".repeat(129)})
            assertThrows(BizException.class, () -> service.resetMemberPassword(17, value, value));
        assertThrows(BizException.class, () -> service.resetMemberPassword(17, SYNTHETIC, "different"));
        verify(users, never()).updateById(any(User.class));
    }
    @Test void refusesDisabledWorkspaceAndNonWorkspaceAdministrator() {
        when(tenants.selectById(10)).thenReturn(null);
        assertThrows(BizException.class, () -> service.resetMemberPassword(17, SYNTHETIC, SYNTHETIC));
        when(security.currentManagedTenantId()).thenThrow(new BizException("not workspace administrator"));
        assertThrows(BizException.class, () -> service.resetMemberPassword(17, SYNTHETIC, SYNTHETIC));
        verify(users, never()).updateById(any(User.class));
    }
}
