package io.studentcloud.platform.http;

import io.studentcloud.account.WorkspaceOwnershipTransferService;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="student-cloud.ownership-transfer.enabled",havingValue="true")
@RequestMapping("/api/workspace-admin/ownership-transfers")
public class WorkspaceOwnershipTransferController {
    private final WorkspaceOwnershipTransferService transfers;
    public WorkspaceOwnershipTransferController(WorkspaceOwnershipTransferService transfers){this.transfers=transfers;}
    @ModelAttribute void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    public record Create(@Positive long toAccountId,@PositiveOrZero long expectedRevision,long expiresAt,@NotBlank @Size(max=100) String idempotencyKey){}
    @GetMapping public ApiResponse<WorkspaceOwnershipTransferService.Snapshot> snapshot(HttpServletRequest request){return ApiResponse.success(transfers.snapshot(AuthenticationFilter.actor(request)));}
    @PostMapping public ApiResponse<WorkspaceOwnershipTransferService.Transfer> create(@Valid @RequestBody Create input,HttpServletRequest request){return ApiResponse.success(transfers.create(AuthenticationFilter.actor(request),input.toAccountId(),input.expectedRevision(),input.expiresAt(),input.idempotencyKey()));}
    @PostMapping("/{id}/accept") public ApiResponse<WorkspaceOwnershipTransferService.Transfer> accept(@PathVariable String id,HttpServletRequest request){return decide(request,id,"accept");}
    @PostMapping("/{id}/reject") public ApiResponse<WorkspaceOwnershipTransferService.Transfer> reject(@PathVariable String id,HttpServletRequest request){return decide(request,id,"reject");}
    @PostMapping("/{id}/cancel") public ApiResponse<WorkspaceOwnershipTransferService.Transfer> cancel(@PathVariable String id,HttpServletRequest request){return decide(request,id,"cancel");}
    private ApiResponse<WorkspaceOwnershipTransferService.Transfer> decide(HttpServletRequest request,String id,String action){return ApiResponse.success(transfers.decide(AuthenticationFilter.actor(request),id,action));}
}
