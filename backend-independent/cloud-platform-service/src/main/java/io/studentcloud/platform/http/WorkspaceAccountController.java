package io.studentcloud.platform.http;

import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.RequestTokens;

import io.studentcloud.account.AccountAdministration;
import io.studentcloud.account.AccountView;
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

@RestController
public class WorkspaceAccountController {
    private final AccountAdministration accounts;
    private final WorkspaceMemberQuery members;
    public WorkspaceAccountController(AccountAdministration accounts,WorkspaceMemberQuery members) { this.accounts = accounts;this.members=members; }

    public record AdministratorInput(@NotBlank @Size(max=64) String username, @NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=8,max=128) String password, @NotBlank @Size(max=120) String workspaceName) { }
    public record MemberInput(@NotBlank @Size(max=64) String username, @Email @Size(max=254) String email,
            @NotBlank @Size(min=8,max=128) String password, @Size(max=100) String nickname) { }
    public record PasswordInput(@NotBlank @Size(min=8,max=128) String password) { }

    @PostMapping("/api/platform/workspace-administrators")
    public ApiResponse<AccountView> administrator(@Valid @RequestBody AdministratorInput input, HttpServletRequest request) {
        return ApiResponse.success(accounts.createWorkspaceAdministrator(AuthenticationFilter.actor(request), input.username(), input.email(), input.password(), input.workspaceName()));
    }

    @GetMapping("/api/platform/workspace-administrators")
    public ApiResponse<List<AccountView>> administrators(@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        return new ApiResponse<>("0", "ok", accounts.listWorkspaceAdministrators(actor, offset, limit), accounts.countWorkspaceAdministrators(actor), null);
    }

    @GetMapping("/api/workspace-admin/members")
    public ApiResponse<WorkspaceMemberQuery.Page> members(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="") String search, HttpServletRequest request) {
        return ApiResponse.success(members.page(AuthenticationFilter.actor(request),page,limit,search));
    }

    @PostMapping("/api/workspace-admin/members")
    public ApiResponse<AccountView> create(@Valid @RequestBody MemberInput input, HttpServletRequest request) {
        var member=accounts.createMember(AuthenticationFilter.actor(request), input.username(), input.email(), input.password(),input.nickname());
        return ApiResponse.success(member);
    }

    @PostMapping("/api/workspace-admin/members/{id}/enabled")
    public ApiResponse<Void> enable(@PathVariable long id, @RequestParam boolean value, HttpServletRequest request) {
        accounts.setMemberEnabled(AuthenticationFilter.actor(request), id, value); return ApiResponse.success(null);
    }

    @PostMapping("/api/workspace-admin/members/{id}/password")
    public ApiResponse<Void> password(@PathVariable long id, @Valid @RequestBody PasswordInput input, HttpServletRequest request) {
        accounts.resetMemberPassword(AuthenticationFilter.actor(request), id, input.password()); return ApiResponse.success(null);
    }
}
