package io.studentcloud.content.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.drive.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class CloudAccessController {
    private final WorkspaceCloudService clouds;
    public CloudAccessController(WorkspaceCloudService clouds) { this.clouds = clouds; }
    public record Approval(boolean approve, Long quotaBytes, Set<String> permissions, @Size(max=500) String reason) { }
    public record GrantInput(Long quotaBytes, Set<String> permissions) { }
    public record LocalRequest(@NotBlank @Size(max=120) String name) { }
    public record ApplicationInput(@Size(max=500) String reason){}

    @PostMapping("/api/workspace-cloud/{id}/apply")
    public ApiResponse<CloudAccessRequestView> apply(@PathVariable long id,@Valid @RequestBody(required=false) ApplicationInput input, HttpServletRequest request) {
        return ApiResponse.success(clouds.requestCloud(AuthenticationFilter.actor(request), id,input==null?null:input.reason()));
    }
    @GetMapping("/api/workspace-cloud/my-requests")
    public ApiResponse<List<CloudAccessRequestView>> own(HttpServletRequest request) {
        return ApiResponse.success(clouds.listMyRequests(AuthenticationFilter.actor(request)));
    }
    @GetMapping("/api/workspace-cloud/requests")
    public ApiResponse<List<CloudAccessRequestView>> pending(HttpServletRequest request) {
        return ApiResponse.success(clouds.pendingForWorkspaceAdmin(AuthenticationFilter.actor(request)));
    }
    @PostMapping("/api/workspace-cloud/requests/{id}/review")
    public ApiResponse<CloudAccessRequestView> review(@PathVariable long id, @RequestBody Approval input, HttpServletRequest request) {
        return ApiResponse.success(input.approve()
                ? clouds.approveCloud(AuthenticationFilter.actor(request), id, input.quotaBytes(), input.permissions())
                : clouds.rejectCloud(AuthenticationFilter.actor(request), id, input.reason()));
    }
    @PutMapping("/api/workspace-admin/members/{userId}/drives/{mountId}/grant")
    public ApiResponse<Void> grant(@PathVariable long userId, @PathVariable long mountId, @RequestBody GrantInput input, HttpServletRequest request) {
        clouds.updateMemberGrant(AuthenticationFilter.actor(request), userId, mountId, input.quotaBytes(), input.permissions());
        return ApiResponse.success(null);
    }
    @PostMapping("/api/local-storage-requests")
    public ApiResponse<LocalStorageRequestView> local(@Valid @RequestBody LocalRequest input, HttpServletRequest request) {
        return ApiResponse.success(clouds.requestLocal(AuthenticationFilter.actor(request), input.name()));
    }
    @GetMapping("/api/local-storage-requests")
    public ApiResponse<List<LocalStorageRequestView>> ownLocal(HttpServletRequest request) {
        return ApiResponse.success(clouds.listMyLocalRequests(AuthenticationFilter.actor(request)));
    }
    @GetMapping("/admin/local-storage-requests")
    public ApiResponse<List<LocalStorageRequestView>> pendingLocal(HttpServletRequest request) {
        return ApiResponse.success(clouds.pendingLocalForGlobalAdmin(AuthenticationFilter.actor(request)));
    }
    @PostMapping("/admin/local-storage-requests/{id}/review")
    public ApiResponse<LocalStorageRequestView> approveLocal(@PathVariable long id, @RequestBody Approval input, HttpServletRequest request) {
        return ApiResponse.success(input.approve() ? clouds.approveLocal(AuthenticationFilter.actor(request), id)
                : clouds.rejectLocal(AuthenticationFilter.actor(request), id, input.reason()));
    }
}
