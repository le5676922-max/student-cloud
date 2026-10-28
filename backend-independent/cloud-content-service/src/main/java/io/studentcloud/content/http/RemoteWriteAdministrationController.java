package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.content.remote.RemoteDriveService;
import io.studentcloud.content.remote.RemoteStorageAdministration;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Inspection is explicit and does not replay writes or relax reservations on ambiguous evidence. */
@RestController
public final class RemoteWriteAdministrationController {
    private final RemoteStorageAdministration connections;
    private final RemoteDriveService drives;
    public RemoteWriteAdministrationController(RemoteStorageAdministration connections,RemoteDriveService drives){
        this.connections=connections;this.drives=drives;
    }
    @GetMapping("/admin/storage/{id}/pending-writes")
    public ApiResponse<List<Map<String,Object>>> pending(@PathVariable long id,HttpServletRequest request){
        var actor=AuthenticationFilter.actor(request);
        String key=(String)connections.detail(actor,id).get("key");
        return ApiResponse.success(drives.pendingOperations(actor,key));
    }
    @PostMapping("/admin/storage/{id}/pending-writes/{operationId}/inspect")
    public ApiResponse<Map<String,Object>> inspect(@PathVariable long id,@PathVariable String operationId,HttpServletRequest request){
        var actor=AuthenticationFilter.actor(request);
        String key=(String)connections.detail(actor,id).get("key");
        return ApiResponse.success(drives.inspectPending(actor,key,operationId));
    }
}
