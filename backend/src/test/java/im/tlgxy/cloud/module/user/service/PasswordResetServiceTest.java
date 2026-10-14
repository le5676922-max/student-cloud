package im.tlgxy.cloud.module.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.mapper.UserMapper;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.model.request.PasswordResetRequest;
import im.tlgxy.cloud.module.user.utils.PasswordVerifyUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {
    @TempDir Path work;
    UserMapper users;
    UserService userService;
    EmailVerificationService verification;
    PasswordResetSessionInvalidator sessions;
    PasswordResetService service;
    User user;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "reset-test"), User.class);
        users = mock(UserMapper.class);
        userService = mock(UserService.class);
        verification = mock(EmailVerificationService.class);
        sessions = mock(PasswordResetSessionInvalidator.class);
        var manager = new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:sqlite:" + work.resolve("reset.db")));
        service = new PasswordResetService(users, userService, verification, sessions, manager);
        user = new User();
        user.setId(9); user.setEmail("member@example.test"); user.setEnable(true); user.setRole(UserRoleEnum.USER);
        user.setPassword("old-encoded-password"); user.setSalt("old-salt");
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user));
        when(userService.requireLoginUser(9)).thenReturn(user);
        when(users.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @Test void sendCodeNeverChecksOrLeaksAccountExistence() {
        service.sendEmailCode("  Member@Example.test ");
        service.sendEmailCode("unknown@example.test");
        verify(verification).sendCode("member@example.test", EmailVerificationService.PASSWORD_RESET);
        verify(verification).sendCode("unknown@example.test", EmailVerificationService.PASSWORD_RESET);
        verifyNoInteractions(users, userService, sessions);
    }

    @Test void resetOnlyWritesSaltedHashThenInvalidatesAllSessionsAfterCommit() {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "consumption is independently committed");
            return null;
        }).when(verification).consumeCode(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "logout occurs after password commit");
            return null;
        }).when(sessions).invalidate(9);
        service.reset(request("  exact password  ", "  exact password  "));
        verify(verification).consumeCode("member@example.test", EmailVerificationService.PASSWORD_RESET, "123456");
        var captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(users).update(isNull(), captor.capture());
        var update = (LambdaUpdateWrapper<User>) captor.getValue();
        assertTrue(update.getSqlSegment().contains("email"), "bound email is rechecked during the update");
        var params = update.getParamNameValuePairs().values();
        String hash = params.stream().map(Object::toString).filter(s -> s.startsWith("pbkdf2-sha256$")).findFirst().orElseThrow();
        String salt = params.stream().map(Object::toString).filter(s -> s.matches("[A-Za-z0-9+/]{22}==")).findFirst().orElseThrow();
        assertTrue(PasswordVerifyUtils.verify(hash, salt, "  exact password  "));
        assertFalse(params.contains("  exact password  "));
        verify(sessions).invalidate(9);
    }

    @Test void missingDisabledInternalAndAmbiguousAccountsProduceSameGenericOutcome() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user, user));
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user));
        user.setEnable(false);
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        user.setEnable(true); user.setId(0);
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        user.setId(2);
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        verify(users, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(sessions);
    }

    @Test void suspendedWorkspaceOrUnapprovedMembershipCannotUseRecoveryToBypassState() {
        when(userService.requireLoginUser(9)).thenThrow(new BizException("space unavailable"));
        assertDoesNotThrow(() -> service.reset(request("Password123", "Password123")));
        verify(users, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(sessions);
    }

    @Test void invalidPasswordsAndCodesCannotWriteOrInvalidateSessions() {
        assertThrows(BizException.class, () -> service.reset(request("short", "short")));
        assertThrows(BizException.class, () -> service.reset(request("Password123", "Different123")));
        assertThrows(BizException.class, () -> service.reset(request(" ".repeat(8), " ".repeat(8))));
        assertThrows(BizException.class, () -> service.reset(request("x".repeat(129), "x".repeat(129))));
        verifyNoInteractions(verification, users, sessions);
        doThrow(new BizException("bad code")).when(verification).consumeCode(anyString(), anyString(), anyString());
        assertThrows(BizException.class, () -> service.reset(request("Password123", "Password123")));
        verifyNoInteractions(users, sessions);
    }

    @Test void racingAccountUpdateDoesNotClaimSuccessOrLogoutAfterRolledBackWrite() {
        when(users.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThrows(BizException.class, () -> service.reset(request("Password123", "Password123")));
        verify(verification).consumeCode("member@example.test", EmailVerificationService.PASSWORD_RESET, "123456");
        verifyNoInteractions(sessions);
    }

    private PasswordResetRequest request(String password, String confirmation) {
        return new PasswordResetRequest(" Member@Example.test ", "123456", password, confirmation);
    }
}
