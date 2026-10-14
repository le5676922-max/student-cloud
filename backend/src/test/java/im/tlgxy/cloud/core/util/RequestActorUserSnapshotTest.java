package im.tlgxy.cloud.core.util;

import im.tlgxy.cloud.module.user.model.constant.UserConstant;
import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.enums.UserRoleEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestActorUserSnapshotTest {

    private MockedStatic<TlgxyCloudAuthUtil> authentication;

    @BeforeEach
    void bindAuthenticatedReadRequest() {
        authentication = mockStatic(TlgxyCloudAuthUtil.class);
        authentication.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(9);
        bindRequest("GET");
    }

    @AfterEach
    void releaseRequestAndAuthentication() {
        RequestContextHolder.resetRequestAttributes();
        authentication.close();
    }

    @Test
    void currentActorIsReadOncePerReadOnlyRequest() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        assertEquals(9, RequestActorUserSnapshot.load(9, database).getId());
        assertEquals(9, RequestActorUserSnapshot.load(9, database).getId());
        assertEquals(1, reads.get());
        bindRequest("HEAD");
        RequestActorUserSnapshot.load(9, database);
        RequestActorUserSnapshot.load(9, database);
        assertEquals(2, reads.get());
    }

    @Test
    void nextRequestDoesNotReuseTheOldActorSnapshot() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        RequestActorUserSnapshot.load(9, database);
        bindRequest("GET");
        RequestActorUserSnapshot.load(9, database);
        assertEquals(2, reads.get());
    }

    @Test
    void mutationsCannotPolluteTheSavedEntityOrPermissionCollections() {
        User original = user(9);
        original.setDefaultPermissions(new HashSet<>(Set.of("read")));
        original.setCreateTime(new Date(100));
        original.setUpdateTime(new Date(200));
        User first = RequestActorUserSnapshot.load(9, () -> original);
        first.setRole(UserRoleEnum.ADMIN);
        first.getDefaultPermissions().add("delete");
        first.getCreateTime().setTime(300);
        User second = RequestActorUserSnapshot.load(9, () -> fail("Unexpected database read"));
        assertEquals(UserRoleEnum.USER, second.getRole());
        assertEquals(Set.of("read"), second.getDefaultPermissions());
        assertEquals(100, second.getCreateTime().getTime());
        second.setEnable(false);
        second.getDefaultPermissions().clear();
        second.getUpdateTime().setTime(400);
        User third = RequestActorUserSnapshot.load(9, () -> fail("Unexpected database read"));
        assertTrue(third.getEnable());
        assertEquals(Set.of("read"), third.getDefaultPermissions());
        assertEquals(200, third.getUpdateTime().getTime());
    }

    @Test
    void targetUserReadsAreNotMistakenForTheCurrentActor() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(10); };
        RequestActorUserSnapshot.load(10, database);
        RequestActorUserSnapshot.load(10, database);
        assertEquals(2, reads.get());
    }

    @Test
    void writeMethodsAlwaysUseFreshDatabaseReads() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        for (String method : Set.of("POST", "PUT", "PATCH", "DELETE")) {
            bindRequest(method);
            RequestActorUserSnapshot.load(9, database);
            RequestActorUserSnapshot.load(9, database);
        }
        assertEquals(8, reads.get());
    }

    @Test
    void callsOutsideAnHttpRequestAlwaysReadTheDatabase() {
        RequestContextHolder.resetRequestAttributes();
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        RequestActorUserSnapshot.load(9, database);
        RequestActorUserSnapshot.load(9, database);
        assertEquals(2, reads.get());
    }

    @Test
    void backgroundJobsCannotReuseAnHttpSnapshotEvenIfAttributesArePresent() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        RequestActorUserSnapshot.load(9, database);
        BackgroundActorContext.runAs(9, () -> {
            RequestActorUserSnapshot.load(9, database);
            RequestActorUserSnapshot.load(9, database);
        });
        assertEquals(3, reads.get());
        assertNull(BackgroundActorContext.currentActor());
    }

    @Test
    void failedOrRetiredIdentityIsNeverRetained() {
        for (User candidate : new User[]{null, disabledUser(), retiredUser(), templateUser(), wrongIdUser()}) {
            bindRequest("GET");
            AtomicInteger reads = new AtomicInteger();
            Supplier<User> database = () -> { reads.incrementAndGet(); return candidate; };
            RequestActorUserSnapshot.load(9, database);
            RequestActorUserSnapshot.load(9, database);
            assertEquals(2, reads.get());
        }
    }

    @Test
    void unauthenticatedOrChangedActorCannotReadTheOtherSnapshot() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        RequestActorUserSnapshot.load(9, database);
        authentication.when(TlgxyCloudAuthUtil::getCurrentUserId).thenReturn(10);
        RequestActorUserSnapshot.load(9, database);
        authentication.when(TlgxyCloudAuthUtil::getCurrentUserId).thenThrow(new IllegalStateException("No actor"));
        RequestActorUserSnapshot.load(9, database);
        assertEquals(3, reads.get());
    }

    @Test
    void invalidationRequiresANewReadAndDoesNotRemoveAnUnrelatedIdentity() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<User> database = () -> { reads.incrementAndGet(); return user(9); };
        RequestActorUserSnapshot.load(9, database);
        RequestActorUserSnapshot.invalidate(10);
        RequestActorUserSnapshot.load(9, database);
        assertEquals(1, reads.get());
        RequestActorUserSnapshot.invalidate(9);
        RequestActorUserSnapshot.load(9, database);
        assertEquals(2, reads.get());
    }

    private void bindRequest(String method) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, "/admin/storages")));
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        user.setEnable(true);
        user.setRole(UserRoleEnum.USER);
        user.setTenantId(2);
        return user;
    }

    private User disabledUser() { User user = user(9); user.setEnable(false); return user; }
    private User retiredUser() { User user = user(9); user.setRole(null); return user; }
    private User templateUser() { return user(UserConstant.NEW_USER_TEMPLATE_ID); }
    private User wrongIdUser() { return user(10); }
}
