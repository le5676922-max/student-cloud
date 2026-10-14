package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.service.WorkspaceEnrollmentService;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.PublicRegistrationRequest;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicRegistrationServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final EmailVerificationService email = mock(EmailVerificationService.class);
    private final WorkspaceEnrollmentService enrollment = mock(WorkspaceEnrollmentService.class);
    private PublicRegistrationService service;

    @BeforeEach void setUp() {
        service = new PublicRegistrationService(users, email, enrollment);
        when(users.selectCount(any(Wrapper.class))).thenReturn(0L);
    }

    @Test void globalAdministratorIsNeverAPublicRegistrationChoice() {
        assertThrows(BizException.class, () -> service.register(request("ADMIN", "Password123", "Password123")));
        verifyNoInteractions(email, enrollment);
    }

    @Test void mismatchFailsBeforeConsumingEmailCode() {
        assertThrows(BizException.class, () -> service.register(request("TENANT_ADMIN", "Password123", "WrongPassword123")));
        verifyNoInteractions(email, enrollment);
    }

    @Test void emailIsNormalizedAndSystemAccountAndSaltedPasswordAreGenerated() {
        Tenant tenant = new Tenant(); tenant.setId(8); tenant.setName("新工作区");
        when(enrollment.createWorkspace(any(User.class), eq("新工作区"))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setRole(UserRoleEnum.TENANT_ADMIN);
            return new WorkspaceEnrollmentService.Enrollment(tenant, "ACTIVE");
        });
        var result = service.register(request("TENANT_ADMIN", "Password123", "Password123"));
        verify(email).consumeCode("student@example.com", "REGISTER", "123456");
        var user = ArgumentCaptor.forClass(User.class);
        verify(enrollment).createWorkspace(user.capture(), eq("新工作区"));
        assertTrue(result.accountId().matches("SC[0-9A-F]{16}"));
        assertEquals(result.accountId(), user.getValue().getUsername());
        assertEquals("student@example.com", user.getValue().getEmail());
        assertNotEquals("Password123", user.getValue().getPassword());
        assertTrue(PasswordVerifyUtils.verify(user.getValue().getPassword(), user.getValue().getSalt(), "Password123"));
    }

    @Test void unavailableOrInvalidEmailCodeDoesNotEnterRegistrationTransaction() {
        doThrow(new BizException("验证码无效")).when(email).consumeCode(anyString(), anyString(), anyString());
        assertThrows(BizException.class, () -> service.register(request("TENANT_ADMIN", "Password123", "Password123")));
        verifyNoInteractions(enrollment);
    }

    @Test void duplicateEmailOrLegacyLoginAliasCannotRegister() {
        when(users.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(BizException.class, () -> service.register(request("TENANT_ADMIN", "Password123", "Password123")));
        verifyNoInteractions(email, enrollment);
    }

    private PublicRegistrationRequest request(String type, String password, String confirmation) {
        return new PublicRegistrationRequest(" Student@Example.COM ", "123456", password, confirmation,
                "同学", type, "新工作区", null, null);
    }
}
