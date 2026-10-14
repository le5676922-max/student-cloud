package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.mapper.WorkspaceJoinRequestMapper;
import im.tlgxy.cloud.module.tenant.model.entity.WorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserMembershipApprovalGuardTest {
    @BeforeAll
    static void initializeJoinMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "membership-test"),
                WorkspaceJoinRequest.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unapprovedMembershipCannotEstablishOrReuseLoginEvenIfEnabled() {
        var users = mock(UserMapper.class);
        when(users.selectById(9)).thenReturn(member());
        var joins = mock(WorkspaceJoinRequestMapper.class);
        when(joins.selectCount(any())).thenReturn(1L);
        UserService service = service(users, joins, mock(TenantSecurityService.class));
        assertThrows(BizException.class, () -> service.requireLoginUser(9));
        ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(joins).selectCount(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.contains("status <>"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(9));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("APPROVED"));
    }

    @Test
    void genericAdministrationCannotEnableUnapprovedMembership() {
        var users = mock(UserMapper.class);
        var joins = mock(WorkspaceJoinRequestMapper.class);
        when(joins.selectCount(any())).thenReturn(1L);
        var security = mock(TenantSecurityService.class);
        when(security.requireManageUser(9)).thenReturn(member());
        UserService service = service(users, joins, security);
        assertThrows(BizException.class, () -> service.updateUserEnable(9, true));
        SaveUserRequest request = new SaveUserRequest();
        request.setId(9);
        request.setEnable(true);
        assertThrows(BizException.class, () -> service.saveOrUpdate(request));
        verifyNoInteractions(users);
    }

    @Test
    void accountsWithoutUnapprovedJoinRecordsRemainEligible() {
        var users = mock(UserMapper.class);
        User member = member();
        when(users.selectById(9)).thenReturn(member);
        var joins = mock(WorkspaceJoinRequestMapper.class);
        when(joins.selectCount(any())).thenReturn(0L);
        UserService service = service(users, joins, mock(TenantSecurityService.class));
        assertSame(member, service.requireLoginUser(9));
    }

    private UserService service(UserMapper users, WorkspaceJoinRequestMapper joins, TenantSecurityService security) {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "workspaceJoinRequestMapper", joins);
        ReflectionTestUtils.setField(service, "tenantService", mock(TenantService.class));
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        return service;
    }

    private User member() {
        User member = new User();
        member.setId(9);
        member.setRole(UserRoleEnum.USER);
        member.setTenantId(1);
        member.setEnable(true);
        return member;
    }
}
