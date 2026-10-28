package io.studentcloud.platform.http;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.AccountService;
import io.studentcloud.account.Actor;
import io.studentcloud.account.WorkspaceDirectory;
import io.studentcloud.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** Independent service credential is validated before these endpoints; no public gateway route exposes them. */
@RestController
@RequestMapping("/internal/identity")
public final class IdentityInternalController {
    private final AccountService accounts;
    private final AccountDirectory directory;
    public IdentityInternalController(AccountService accounts,AccountDirectory directory) {this.accounts=accounts;this.directory=directory;}
    public record SessionInput(@NotBlank @Size(min=43,max=43) String token) { }
    @PostMapping("/session") public ApiResponse<Actor> session(@Valid @RequestBody SessionInput input,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(accounts.authenticate(input.token()));
    }
    @GetMapping("/accounts/{id}") public ApiResponse<Actor> actor(@PathVariable long id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(directory.account(id));
    }
    @GetMapping("/workspaces/{id}/enabled") public ApiResponse<Boolean> workspace(@PathVariable long id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(directory.workspaceEnabled(id));
    }
    @GetMapping("/workspaces/{id}/administrators") public ApiResponse<java.util.List<Actor>> administrators(@PathVariable long id,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        if(!(directory instanceof WorkspaceDirectory workspaces))throw new io.studentcloud.common.ApplicationFault("IDENTITY_UNAVAILABLE",503,"空间管理员服务暂不可用");
        return ApiResponse.success(workspaces.administrators(id));
    }
}
