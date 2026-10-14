package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.admin.service.GlobalOverviewService;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalOverviewControllerTest {
    @Test
    void bothHttpEntrypointsAuthorizeBeforeDelegating() {
        var service = mock(GlobalOverviewService.class);
        var security = mock(TenantSecurityService.class);
        var controller = new GlobalOverviewController(service, security);
        doThrow(new BizException("system admin required")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, controller::overview);
        assertThrows(BizException.class, () -> controller.workspaces("x", true, 0, 0, 10));
        verify(security, times(2)).requireSystemAdmin();
        verifyNoInteractions(service);
    }

    @Test
    void authorizedRequestsForwardFilterAndPagingUnchanged() {
        var service = mock(GlobalOverviewService.class);
        var security = mock(TenantSecurityService.class);
        var controller = new GlobalOverviewController(service, security);
        controller.overview();
        controller.workspaces("  云盘 ", false, 3, 25, 10);
        var order = inOrder(security, service);
        order.verify(security).requireSystemAdmin();
        order.verify(service).overview();
        order.verify(security).requireSystemAdmin();
        order.verify(service).workspaces("  云盘 ", false, 3, 25, 10);
        order.verifyNoMoreInteractions();
    }

    @Test
    void memberEntrypointAuthorizesBeforeDelegatingAndPreservesExplicitWorkspaceScope() {
        var service = mock(GlobalOverviewService.class);
        var security = mock(TenantSecurityService.class);
        var controller = new GlobalOverviewController(service, security);
        doThrow(new BizException("system admin required")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> controller.workspaceMembers(10, "USER", "x", 1, 10));
        verifyNoInteractions(service);
        reset(security);
        controller.workspaceMembers(20, "TENANT_ADMIN", "SC123", 2, 25);
        var order = inOrder(security, service);
        order.verify(security).requireSystemAdmin();
        order.verify(service).workspaceMembers(20, "TENANT_ADMIN", "SC123", 2, 25);
        order.verifyNoMoreInteractions();
    }
}
