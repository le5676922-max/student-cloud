package io.studentcloud.content.http;

import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.drive.application.WorkspaceCloudService;
import io.studentcloud.runtime.http.HttpFailures;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CloudAccessControllerTest {
    @Test void updatingMemberGrantDoesNotRequireAnApprovalDecisionField() throws Exception {
        var clouds=mock(WorkspaceCloudService.class);
        var actor=new Actor(2,"owner","owner","owner@example.invalid",Role.TENANT_ADMIN,1L);
        var mvc=MockMvcBuilders.standaloneSetup(new CloudAccessController(clouds)).setControllerAdvice(new HttpFailures()).build();
        mvc.perform(put("/api/workspace-admin/members/3/drives/1/grant")
                .requestAttr(Actor.class.getName(),actor).contentType("application/json")
                .content("{\"quotaBytes\":32768,\"permissions\":[\"list\",\"read\"]}"))
                .andExpect(status().isOk());
        verify(clouds).updateMemberGrant(actor,3,1,32768L,Set.of("list","read"));
    }
}
