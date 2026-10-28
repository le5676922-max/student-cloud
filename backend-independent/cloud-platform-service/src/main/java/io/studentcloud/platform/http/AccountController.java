package io.studentcloud.platform.http;

import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.RequestTokens;

import io.studentcloud.account.AccountService;
import io.studentcloud.account.AccountView;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.account.PublicRegistrationService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
    private final AccountService accounts;
    private final PublicRegistrationService profiles;
    public AccountController(AccountService accounts,PublicRegistrationService profiles) { this.accounts = accounts;this.profiles=profiles; }

    public record InstallRequest(@NotBlank @Size(max=80) String siteName, @NotBlank @Size(max=64) String username,
                                 @NotBlank @Size(min=8,max=128) String password) { }
    public record LoginRequest(@NotBlank @Size(max=254) String username, @NotBlank @Size(max=128) String password) { }
    public record PasswordRequest(@NotBlank @Size(max=128) String oldPassword, @NotBlank @Size(min=8,max=128) String newPassword) { }

    @GetMapping("/api/install/status")
    public ApiResponse<Boolean> installed() { return ApiResponse.success(accounts.installationStatus()); }

    @PostMapping("/api/install")
    public ApiResponse<Map<String, Object>> install(@Valid @RequestBody InstallRequest input) {
        return ApiResponse.success(view(accounts.install(input.siteName(), input.username(), input.password())));
    }

    @PostMapping("/user/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest input) {
        var login = accounts.login(input.username(), input.password());
        var data = withProfile(view(login.account()));
        data.put("token", login.token());
        data.put("expiresAt", login.expiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    @GetMapping("/user/login/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> check(HttpServletRequest request) {
        Map<String, Object> identity;
        try { identity = withProfile(view(accounts.authenticate(RequestTokens.extract(request)))); }
        catch (ApplicationFault invalid) {
            if (invalid.httpStatus() != 401) throw invalid;
            identity = Map.of("isLogin", false);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(identity));
    }

    @PostMapping("/user/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        accounts.logout(RequestTokens.extract(request));
        return ApiResponse.success(null);
    }

    @PostMapping("/user/updatePwd")
    public ApiResponse<Void> password(@Valid @RequestBody PasswordRequest input, HttpServletRequest request) {
        accounts.changePassword(AuthenticationFilter.actor(request).id(), input.oldPassword(), input.newPassword());
        return ApiResponse.success(null);
    }

    static Map<String, Object> view(AccountView account) {
        return identity(account.id(), account.accountId(), account.username(), account.email(), account.role(), account.workspaceId());
    }
    private Map<String,Object> withProfile(Map<String,Object> data){
        data.put("nickname",profiles.nickname(((Number)data.get("id")).longValue(),(String)data.get("username")));return data;
    }
    static Map<String, Object> view(Actor actor) {
        return identity(actor.id(), actor.accountId(), actor.username(), actor.email(), actor.role(), actor.workspaceId());
    }
    private static Map<String, Object> identity(long id, String accountId, String username, String email, Role role, Long workspaceId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("accountId", accountId); result.put("username", username);
        result.put("nickname", username); result.put("email", email); result.put("role", role.name());
        result.put("tenantId", workspaceId); result.put("isLogin", true);
        result.put("isAdmin", role == Role.ADMIN); result.put("admin", role == Role.ADMIN);
        return result;
    }
}
