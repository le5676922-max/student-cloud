package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.TenantService;
import im.tlgxy.cloud.module.user.manager.UserManager;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.mapper.UserStorageSourceMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.entity.UserStorageSource;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.CopyUserRequest;
import im.tlgxy.cloud.module.user.model.request.QueryUserRequest;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class UserRetirementTest {
    @BeforeAll
    static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "retirement-test"), User.class);
    }

    @Test
    void accountApiRejectsRetiredAndUnknownRoles() throws Exception {
        var json = new ObjectMapper();
        for (String role : List.of("ADMIN", "TENANT_ADMIN", "USER")) {
            assertEquals(UserRoleEnum.valueOf(role), json.readValue("{\"role\":\"" + role + "\"}", SaveUserRequest.class).getRole());
        }
        for (String role : List.of("GUEST", "SUPER_ADMIN", "UNKNOWN")) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> json.readValue("{\"role\":\"" + role + "\"}", SaveUserRequest.class));
        }
        assertEquals(UserRoleEnum.GUEST, UserRoleEnum.valueOf("GUEST")); // Historical database reads remain supported.
    }

    @Test
    void retiredIdentityAndHistoricalRolesCannotReuseSessionsEvenIfEnabled() {
        var mapper = mock(UserMapper.class);
        when(mapper.selectById(2)).thenReturn(user(2, UserRoleEnum.USER));
        when(mapper.selectById(9)).thenReturn(user(9, UserRoleEnum.GUEST));
        UserService service = service(mapper, mock(TenantSecurityService.class));
        assertThrows(BizException.class, () -> service.requireActiveUser(2));
        assertThrows(BizException.class, () -> service.requireLoginUser(2));
        assertThrows(BizException.class, () -> service.requireActiveUser(9));
        assertThrows(BizException.class, () -> service.requireLoginUser(9));
    }

    @Test
    void administrationCannotExposeOrReactivateRetiredAccounts() {
        var mapper = mock(UserMapper.class);
        var security = mock(TenantSecurityService.class);
        when(security.requireManageUser(2)).thenReturn(user(2, UserRoleEnum.USER));
        when(security.requireManageUser(9)).thenReturn(user(9, UserRoleEnum.GUEST));
        UserService service = service(mapper, security);
        for (int id : List.of(2, 9)) {
            assertThrows(BizException.class, () -> service.getUserDetailById(id));
            assertThrows(BizException.class, () -> service.updateUserEnable(id, true));
            var save = new SaveUserRequest();
            save.setId(id);
            save.setRole(UserRoleEnum.USER);
            save.setEnable(true);
            assertThrows(BizException.class, () -> service.saveOrUpdate(save));
        }
        var create = new SaveUserRequest();
        create.setRole(UserRoleEnum.GUEST);
        assertThrows(BizException.class, () -> service.saveOrUpdate(create));
        verifyNoInteractions(mapper);
    }

    @Test
    void retiredRoleCannotBeCopiedIntoAnotherAccount() {
        var mapper = mock(UserMapper.class);
        var security = mock(TenantSecurityService.class);
        when(security.requireManageUser(9)).thenReturn(user(9, UserRoleEnum.GUEST));
        var copy = new CopyUserRequest();
        copy.setFromId(9);
        copy.setToUsername("copied-account");
        assertThrows(BizException.class, () -> service(mapper, security).copy(copy));
        verify(mapper, never()).insert(any(User.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void accountListOnlyQueriesLoginRolesAndExcludesBuiltInRecords() {
        var mapper = mock(UserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        service(mapper, mock(TenantSecurityService.class)).listUserDetail(new QueryUserRequest());
        ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment().replace("`", "");
        var parameters = query.getValue().getParamNameValuePairs();
        var idConditions = Pattern.compile("id\\s*<>\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(sql);
        Set<String> excludedIds = new java.util.HashSet<>();
        while (idConditions.find()) {
            excludedIds.add(String.valueOf(parameters.get(idConditions.group(1))));
        }
        assertEquals(Set.of("0", "2"), excludedIds);
        var roleCondition = Pattern.compile("role\\s+IN\\s*\\(([^)]+)\\)").matcher(sql);
        assertTrue(roleCondition.find());
        var roleParameters = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(roleCondition.group(1));
        Set<String> allowedRoles = new java.util.HashSet<>();
        while (roleParameters.find()) {
            allowedRoles.add(String.valueOf(parameters.get(roleParameters.group(1))));
        }
        assertEquals(Set.of("ADMIN", "TENANT_ADMIN", "USER"), allowedRoles);
    }

    @Test
    void newStorageDoesNotGrantPermissionsToRetiredRecords() {
        var users = mock(UserMapper.class);
        User template = user(0, UserRoleEnum.USER);
        User member = user(10, UserRoleEnum.USER);
        when(users.selectList(any())).thenReturn(List.of(template, user(2, UserRoleEnum.USER), user(9, UserRoleEnum.GUEST), member));
        var grants = mock(UserStorageSourceMapper.class);
        var manager = new UserManager();
        ReflectionTestUtils.setField(manager, "userMapper", users);
        ReflectionTestUtils.setField(manager, "userStorageSourceMapper", grants);
        manager.addDefaultPermissionsForTenantUsersInStorageSource(123, 1);
        ArgumentCaptor<UserStorageSource> inserted = ArgumentCaptor.forClass(UserStorageSource.class);
        verify(grants, times(2)).insert(inserted.capture());
        assertEquals(List.of(0, 10), inserted.getAllValues().stream().map(UserStorageSource::getUserId).toList());
    }

    @Test
    void retiredBuiltInRecordCannotResolveStoragePermissions() {
        var mapper = mock(UserStorageSourceMapper.class);
        var service = new UserStorageSourceService();
        ReflectionTestUtils.setField(service, "userStorageSourceMapper", mapper);
        assertNull(service.getByUserIdAndStorageId(2, 123));
        verifyNoInteractions(mapper);
    }

    private UserService service(UserMapper mapper, TenantSecurityService security) {
        var service = new UserService();
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantSecurityService", security);
        ReflectionTestUtils.setField(service, "tenantService", mock(TenantService.class));
        return service;
    }

    private User user(int id, UserRoleEnum role) {
        var user = new User();
        user.setId(id);
        user.setRole(role);
        user.setEnable(true);
        user.setTenantId(1);
        user.setDefaultPermissions(Set.of("preview", "download"));
        return user;
    }
}
