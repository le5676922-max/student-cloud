package io.studentcloud.platform.http;

import io.studentcloud.account.WorkspaceEnrollmentService;
import io.studentcloud.account.PublicRegistrationService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/workspace-admin")
public class WorkspaceEnrollmentController {
    private final WorkspaceEnrollmentService enrollment;private final PublicRegistrationService profiles;
    public WorkspaceEnrollmentController(WorkspaceEnrollmentService enrollment,PublicRegistrationService profiles){this.enrollment=enrollment;this.profiles=profiles;}
    public record InvitationInput(Integer expiresInDays,Integer maxUses){ }
    public record ReviewInput(boolean approve){ }
    @GetMapping("/invitations") public ApiResponse<List<WorkspaceEnrollmentService.InvitationView>> invitations(HttpServletRequest request){return ApiResponse.success(enrollment.listInvitations(AuthenticationFilter.actor(request)));}
    @PostMapping("/invitations") public ApiResponse<WorkspaceEnrollmentService.CreatedInvitation> invite(@RequestBody InvitationInput input,HttpServletRequest request){
        return ApiResponse.success(enrollment.createInvitation(AuthenticationFilter.actor(request),input.expiresInDays(),input.maxUses()));
    }
    @DeleteMapping("/invitations/{id}") public ApiResponse<Void> revoke(@PathVariable long id,HttpServletRequest request){enrollment.revokeInvitation(AuthenticationFilter.actor(request),id);return ApiResponse.success(null);}
    @GetMapping("/join-requests") public ApiResponse<List<Map<String,Object>>> requests(HttpServletRequest request){
        var requests=enrollment.joinRequests(AuthenticationFilter.actor(request));
        var names=profiles.nicknamesByAccountIds(requests.stream().map(WorkspaceEnrollmentService.JoinRequestView::accountId).toList());
        return ApiResponse.success(requests.stream().map(item->{
            var data=new LinkedHashMap<String,Object>();data.put("id",item.id());data.put("accountId",item.accountId());data.put("email",item.email());
            data.put("nickname",names.getOrDefault(item.accountId(),item.nickname()));data.put("status",item.status());data.put("createdAt",item.createdAt());return (Map<String,Object>)data;
        }).toList());
    }
    @PostMapping("/join-requests/{id}/review") public ApiResponse<Void> review(@PathVariable long id,@RequestBody ReviewInput input,HttpServletRequest request){
        enrollment.reviewJoinRequest(AuthenticationFilter.actor(request),id,input.approve());return ApiResponse.success(null);
    }
}
