package io.studentcloud.platform.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.account.AccountAdministration;
import io.studentcloud.account.AccountView;
import io.studentcloud.account.PublicRegistrationService;
import io.studentcloud.account.WorkspaceMemberQuery;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 提供空间管理员目录及本空间成员查询、启停与重置邮件接口。 */
@RestController
public class WorkspaceAccountController {
    private final AccountAdministration accounts;
    private final WorkspaceMemberQuery members;
    private final PublicRegistrationService registrations;
    public WorkspaceAccountController(AccountAdministration accounts,WorkspaceMemberQuery members,PublicRegistrationService registrations) {
        this.accounts = accounts;this.members=members;this.registrations=registrations;
    }

    public record AdministratorInput(@NotBlank @Size(max=64) String username, @NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=8,max=128) String password, @NotBlank @Size(max=120) String workspaceName) { }

    /** 由全局管理员创建独立空间及其管理员。 */
    @PostMapping("/api/platform/workspace-administrators")
    public ApiResponse<AccountView> administrator(@Valid @RequestBody AdministratorInput input, HttpServletRequest request) {
        return ApiResponse.success(accounts.createWorkspaceAdministrator(AuthenticationFilter.actor(request), input.username(), input.email(), input.password(), input.workspaceName()));
    }

    /** 分页返回空间管理员列表及总数。 */
    @GetMapping("/api/platform/workspace-administrators")
    public ApiResponse<List<AccountView>> administrators(@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        return new ApiResponse<>("0", "ok", accounts.listWorkspaceAdministrators(actor, offset, limit), accounts.countWorkspaceAdministrators(actor), null);
    }

    /** 读取当前空间成员及其昵称和入驻状态。 */
    @GetMapping("/api/workspace-admin/members")
    public ApiResponse<WorkspaceMemberQuery.Page> members(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="") String search, HttpServletRequest request) {
        return ApiResponse.success(members.page(AuthenticationFilter.actor(request),page,limit,search));
    }

    /** 拒绝旧版直接创建成员请求并提示邀请注册。 */
    @PostMapping("/api/workspace-admin/members")
    public ApiResponse<Void> create(HttpServletRequest request) {
        accounts.rejectDirectMemberCreation(AuthenticationFilter.actor(request));
        return ApiResponse.success(null);
    }

    /** 调整本空间成员启停状态，并在启用前检查加入审批。 */
    @PostMapping("/api/workspace-admin/members/{id}/enabled")
    public ApiResponse<Void> enable(@PathVariable long id, @RequestParam boolean value, HttpServletRequest request) {
        accounts.setMemberEnabled(AuthenticationFilter.actor(request), id, value); return ApiResponse.success(null);
    }

    /** 拒绝管理员代设成员密码的旧版请求。 */
    @PostMapping("/api/workspace-admin/members/{id}/password")
    public ApiResponse<Void> password(@PathVariable long id, HttpServletRequest request) {
        accounts.resetMemberPassword(AuthenticationFilter.actor(request), id, null); return ApiResponse.success(null);
    }

    /** 仅向本空间成员已绑定的邮箱发送密码重置邮件。 */
    @PostMapping("/api/workspace-admin/members/{id}/password-reset-email")
    public ApiResponse<Void> passwordResetEmail(@PathVariable long id,HttpServletRequest request)throws java.io.IOException {
        // No client-supplied password/mailbox is accepted, and rejection reads at most one body byte.
        if(request.getInputStream().read()!=-1) throw new ApplicationFault("41000",400,"此操作不接收密码或邮箱，请直接向该成员绑定邮箱发送重置邮件");
        accounts.sendMemberPasswordResetEmail(AuthenticationFilter.actor(request),id,registrations::requestManagedResetCode);
        return new ApiResponse<>("0","重置邮件已发送，请成员在登录页通过忘记密码验证邮箱并自行设置新密码",null,null,null);
    }
}
