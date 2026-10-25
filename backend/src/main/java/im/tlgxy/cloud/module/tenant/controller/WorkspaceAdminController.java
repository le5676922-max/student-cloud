package im.tlgxy.cloud.module.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.module.tenant.model.request.CreateWorkspaceMemberRequest;
import im.tlgxy.cloud.module.tenant.model.request.CreateWorkspaceInvitationRequest;
import im.tlgxy.cloud.module.tenant.model.request.ReviewWorkspaceJoinRequest;
import im.tlgxy.cloud.module.tenant.service.WorkspaceAdminService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceMemberPermissionService;
import im.tlgxy.cloud.module.tenant.service.WorkspaceEnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("/api/workspace-admin")
public class WorkspaceAdminController {
    private final WorkspaceAdminService service;
    private final WorkspaceEnrollmentService enrollment;
    private final WorkspaceMemberPermissionService permissions;

    @GetMapping("/summary")
    public AjaxJson<WorkspaceAdminService.Summary> summary() { return AjaxJson.getSuccessData(service.summary()); }

    @GetMapping("/members")
    public AjaxJson<WorkspaceAdminService.MemberPage> members(@RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(required = false) String search) {
        return AjaxJson.getSuccessData(service.members(page, search));
    }

    @PostMapping("/members") @ApiLimit(timeout = 60, maxCount = 10) @DemoDisable
    public AjaxJson<WorkspaceAdminService.Member> create(@Valid @RequestBody CreateWorkspaceMemberRequest request) {
        return AjaxJson.getSuccessData(service.create(request));
    }

    @PostMapping("/members/{id}/enabled") @ApiLimit(timeout = 60, maxCount = 20) @DemoDisable
    public AjaxJson<WorkspaceAdminService.Member> setEnabled(@PathVariable int id, @RequestParam boolean value) {
        return AjaxJson.getSuccessData(service.setEnabled(id, value));
    }

    public record MemberPasswordRequest(String password, String confirmPassword) { }

    @PostMapping("/members/{id}/password") @ApiLimit(timeout = 60, maxCount = 10) @DemoDisable
    public AjaxJson<Void> resetPassword(@PathVariable int id, @RequestBody MemberPasswordRequest request) {
        if (request == null) throw new im.tlgxy.cloud.core.exception.core.BizException("请输入新密码");
        service.resetMemberPassword(id, request.password(), request.confirmPassword());
        return AjaxJson.getSuccess();
    }

    @GetMapping("/members/{id}/permissions")
    public AjaxJson<WorkspaceMemberPermissionService.MemberPermissions> permissions(@PathVariable int id) {
        return AjaxJson.getSuccessData(permissions.get(id));
    }

    @PutMapping("/members/{id}/permissions") @ApiLimit(timeout = 60, maxCount = 20) @DemoDisable
    public AjaxJson<Void> permissions(@PathVariable int id,
            @RequestBody WorkspaceMemberPermissionService.UpdateRequest request) {
        permissions.save(id, request);
        return AjaxJson.getSuccess();
    }

    @GetMapping("/invitations")
    public AjaxJson<List<WorkspaceEnrollmentService.InvitationInfo>> invitations() {
        return AjaxJson.getSuccessData(enrollment.invitations());
    }

    @PostMapping("/invitations") @ApiLimit(timeout = 60, maxCount = 10) @DemoDisable
    public AjaxJson<WorkspaceEnrollmentService.CreatedInvitation> createInvitation(
            @Valid @RequestBody CreateWorkspaceInvitationRequest request) {
        return AjaxJson.getSuccessData(enrollment.createInvitation(request));
    }

    @DeleteMapping("/invitations/{id}") @ApiLimit(timeout = 60, maxCount = 20) @DemoDisable
    public AjaxJson<Void> revokeInvitation(@PathVariable int id) {
        enrollment.revokeInvitation(id);
        return AjaxJson.getSuccess();
    }

    @GetMapping("/join-requests")
    public AjaxJson<List<WorkspaceEnrollmentService.JoinRequestInfo>> joinRequests() {
        return AjaxJson.getSuccessData(enrollment.joinRequests());
    }

    @PostMapping("/join-requests/{id}/review") @ApiLimit(timeout = 60, maxCount = 20) @DemoDisable
    public AjaxJson<WorkspaceEnrollmentService.ReviewResult> review(@PathVariable int id,
            @Valid @RequestBody ReviewWorkspaceJoinRequest request) {
        return AjaxJson.getSuccessData(enrollment.review(id, request.approve()));
    }
}
