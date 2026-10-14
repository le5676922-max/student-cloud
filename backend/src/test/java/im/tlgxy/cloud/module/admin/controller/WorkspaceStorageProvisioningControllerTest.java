package im.tlgxy.cloud.module.admin.controller;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceStorageProvisioningService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceStorageProvisioningControllerTest {
    @Test
    void routeChecksGlobalPermissionBeforeProvisioningAndForwardsOnlyTheImmutableId() {
        var security = mock(TenantSecurityService.class);
        var storage = mock(WorkspaceStorageProvisioningService.class);
        var controller = new WorkspaceStorageProvisioningController(security, storage);
        doThrow(new BizException("global only")).when(security).requireSystemAdmin();
        assertThrows(BizException.class, () -> controller.provision(-1));
        verifyNoInteractions(storage);
        reset(security);
        controller.provision(10);
        var order = inOrder(security, storage);
        order.verify(security).requireSystemAdmin();
        order.verify(storage).provisionExistingWorkspace(10);
        order.verifyNoMoreInteractions();
    }
}
