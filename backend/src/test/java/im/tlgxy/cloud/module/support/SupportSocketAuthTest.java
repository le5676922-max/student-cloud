package im.tlgxy.cloud.module.support;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import im.tlgxy.cloud.module.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportSocketAuthTest {
    private final UserService users = mock(UserService.class);
    private final StpLogic logic = mock(StpLogic.class);
    private final MutableClock clock = new MutableClock();
    private final User user = user();
    private final SupportSocketAuth auth = new SupportSocketAuth(users, clock);

    @Test void ticketIsRandomShortLivedSingleUseAndNeverTheLoginToken() {
        try (var stp = mockStatic(StpUtil.class)) {
            wire(stp);
            var ticket = auth.issue(10, "synthetic-login-token");
            assertTrue(ticket.ticket().matches("[A-Za-z0-9_-]{43}"));
            assertFalse(ticket.ticket().contains("synthetic-login-token"));
            assertEquals(30, ticket.expiresInSeconds());
            assertEquals("/ws/support", ticket.path());
            var identity = auth.consume(ticket.ticket());
            assertEquals(10, identity.userId());
            assertFalse(identity.toString().contains(identity.token()));
            assertFalse(identity.toString().contains(identity.fingerprint()));
            assertFalse(ticket.toString().contains(ticket.ticket()));
            assertThrows(BizException.class, () -> auth.consume(ticket.ticket()));
            assertThrows(BizException.class, () -> auth.consume("synthetic-login-token"));
        }
    }

    @Test void expiryAndPerAccountCapacityAreBoundedAndExpiredEntriesCanBeReclaimed() {
        try (var stp = mockStatic(StpUtil.class)) {
            wire(stp);
            var ticket = auth.issue(10, "synthetic-login-token");
            for (int index = 0; index < 3; index++) auth.issue(10, "synthetic-login-token");
            assertThrows(BizException.class, () -> auth.issue(10, "synthetic-login-token"));
            clock.now += 30_000;
            assertThrows(BizException.class, () -> auth.consume(ticket.ticket()));
            assertNotNull(auth.issue(10, "synthetic-login-token"));
        }
    }

    @Test void logoutInvalidatesEvenAnUnusedTicket() {
        try (var stp = mockStatic(StpUtil.class)) {
            wire(stp);
            var ticket = auth.issue(10, "synthetic-login-token");
            when(logic.getLoginIdByToken("synthetic-login-token")).thenReturn(null);
            assertThrows(BizException.class, () -> auth.consume(ticket.ticket()));
        }
    }

    @Test void passwordRoleAndAccountRevocationInvalidateAnEstablishedIdentity() {
        try (var stp = mockStatic(StpUtil.class)) {
            wire(stp);
            var identity = auth.consume(auth.issue(10, "synthetic-login-token").ticket());
            user.setPassword("changed-fingerprint");
            assertThrows(BizException.class, () -> auth.requireValid(identity));
            user.setPassword("synthetic-hash");
            user.setRole(UserRoleEnum.ADMIN);
            assertThrows(BizException.class, () -> auth.requireValid(identity));
            user.setRole(UserRoleEnum.USER);
            when(users.requireLoginUser(10)).thenThrow(new BizException("账号已停用"));
            assertThrows(BizException.class, () -> auth.requireValid(identity));
        }
    }

    @Test void suppliedUserIdCannotChooseAnotherLoggedInIdentity() {
        try (var stp = mockStatic(StpUtil.class)) {
            wire(stp);
            when(logic.getLoginIdByToken("synthetic-login-token")).thenReturn(99);
            assertThrows(BizException.class, () -> auth.issue(10, "synthetic-login-token"));
        }
    }

    private void wire(org.mockito.MockedStatic<StpUtil> stp) {
        stp.when(StpUtil::getStpLogic).thenReturn(logic);
        when(logic.getLoginIdByToken("synthetic-login-token")).thenReturn(10);
        when(users.requireLoginUser(10)).thenReturn(user);
    }
    private static User user() {
        User user = new User(); user.setId(10); user.setEnable(true); user.setRole(UserRoleEnum.USER);
        user.setPassword("synthetic-hash"); user.setSalt("synthetic-salt"); return user;
    }
    private static class MutableClock extends Clock {
        long now = 1000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
