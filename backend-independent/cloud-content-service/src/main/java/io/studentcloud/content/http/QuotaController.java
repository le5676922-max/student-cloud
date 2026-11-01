package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.QuotaLedger;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 本人容量视图与显式校准；不接受用户指定的账户、路径或容量流水。 */
@RestController
public final class QuotaController {
    private final MountService mounts;
    public QuotaController(MountService mounts){this.mounts=mounts;}
    @GetMapping("/api/drives/{key}/quota")
    public ResponseEntity<ApiResponse<QuotaLedger.View>> view(@PathVariable String key,HttpServletRequest request){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(mounts.quota(AuthenticationFilter.actor(request),key)));
    }
    @PostMapping("/api/drives/{key}/quota/reconcile")
    public ResponseEntity<ApiResponse<QuotaLedger.View>> reconcile(@PathVariable String key,HttpServletRequest request){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(mounts.reconcileQuota(AuthenticationFilter.actor(request),key)));
    }
}
