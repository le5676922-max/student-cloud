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

/** 提供当前空间的邀请码管理和成员加入申请审核接口。 */
@RestController @RequestMapping("/api/workspace-admin")
public class WorkspaceEnrollmentController {
    private final WorkspaceEnrollmentService enrollment;private final PublicRegistrationService profiles;
    public WorkspaceEnrollmentController(WorkspaceEnrollmentService enrollment,PublicRegistrationService profiles){this.enrollment=enrollment;this.profiles=profiles;}
    public record InvitationInput(Integer expiresInDays,Integer maxUses){ }
    public record ReviewInput(boolean approve){ }
    /** 列出当前空间的邀请码元数据。 */
    @GetMapping("/invitations") public ApiResponse<List<WorkspaceEnrollmentService.InvitationView>> invitations(HttpServletRequest request){return ApiResponse.success(enrollment.listInvitations(AuthenticationFilter.actor(request)));}
    /** 创建邀请码并在本次响应中返回原文。 */
    @PostMapping("/invitations") public ApiResponse<WorkspaceEnrollmentService.CreatedInvitation> invite(@RequestBody InvitationInput input,HttpServletRequest request){
        return ApiResponse.success(enrollment.createInvitation(AuthenticationFilter.actor(request),input.expiresInDays(),input.maxUses()));
    }
    /** 撤销当前空间的指定邀请码。 */
    @DeleteMapping("/invitations/{id}") public ApiResponse<Void> revoke(@PathVariable long id,HttpServletRequest request){enrollment.revokeInvitation(AuthenticationFilter.actor(request),id);return ApiResponse.success(null);}
    /** 读取当前空间的待审批申请并批量补充昵称。 */
    @GetMapping("/join-requests") public ApiResponse<List<Map<String,Object>>> requests(HttpServletRequest request){
        var requests=enrollment.joinRequests(AuthenticationFilter.actor(request));
        var names=profiles.nicknamesByAccountIds(requests.stream().map(WorkspaceEnrollmentService.JoinRequestView::accountId).toList());
        return ApiResponse.success(requests.stream().map(item->{
            var data=new LinkedHashMap<String,Object>();data.put("id",item.id());data.put("accountId",item.accountId());data.put("email",item.email());
            data.put("nickname",names.getOrDefault(item.accountId(),item.nickname()));data.put("status",item.status());data.put("createdAt",item.createdAt());return (Map<String,Object>)data;
        }).toList());
    }
    /** 提交当前空间成员加入申请的审批结果。 */
    @PostMapping("/join-requests/{id}/review") public ApiResponse<Void> review(@PathVariable long id,@RequestBody ReviewInput input,HttpServletRequest request){
        enrollment.reviewJoinRequest(AuthenticationFilter.actor(request),id,input.approve());return ApiResponse.success(null);
    }
}
