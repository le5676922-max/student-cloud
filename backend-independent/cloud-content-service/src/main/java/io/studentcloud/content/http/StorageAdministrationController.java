package io.studentcloud.content.http;

import io.studentcloud.account.Actor;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.content.application.StorageAdministrationService;
import io.studentcloud.drive.application.LocalStorageRequestView;
import io.studentcloud.drive.application.WorkspaceCloudService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current-admin storage routes backed only by independent platform-owned mount tables. */
@RestController
public final class StorageAdministrationController {
    private final StorageAdministrationService storage;
    private final WorkspaceCloudService cloud;
    private final io.studentcloud.content.remote.RemoteStorageAdministration remote;
    public StorageAdministrationController(StorageAdministrationService storage,WorkspaceCloudService cloud){this(storage,cloud,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public StorageAdministrationController(StorageAdministrationService storage,WorkspaceCloudService cloud,io.studentcloud.content.remote.RemoteStorageAdministration remote){this.storage=storage;this.cloud=cloud;this.remote=remote;}

    public record LocalStorageRequest(@NotBlank @Size(max=500) String reason) { }
    public record LocalStorageReview(boolean approve,@Size(max=500) String note) { }
    public record MemberStorage(Long storageSourceId,String rootPath,Boolean enable,List<String> permissions,Long quotaBytes) { }
    public record MemberPermissionUpdate(List<String> defaultPermissions,List<MemberStorage> storages) { }

    @GetMapping("/admin/support-storage")
    public ApiResponse<List<Map<String,Object>>> supportStorage(HttpServletRequest request){var result=new java.util.ArrayList<>(storage.supportStorage(actor(request)));if(remote!=null)result.addAll(remote.types());return ApiResponse.success(result);}

    @GetMapping("/admin/storage-params")
    public ApiResponse<List<Map<String,Object>>> storageParams(@RequestParam(defaultValue="") String storageType,HttpServletRequest request){return ApiResponse.success(remote!=null&&!"local".equalsIgnoreCase(storageType)?remote.params(actor(request),storageType):storage.storageParams(actor(request),storageType));}

    @GetMapping("/admin/storages")
    public ApiResponse<List<Map<String,Object>>> list(HttpServletRequest request){var rows=new java.util.ArrayList<>(storage.list(actor(request)));if(remote!=null)rows.addAll(remote.list(actor(request)));rows.sort(java.util.Comparator.<Map<String,Object>>comparingInt(row->((Number)row.getOrDefault("orderNum",0)).intValue()).thenComparingLong(row->((Number)row.get("id")).longValue()));return ApiResponse.success(rows);}

    @GetMapping("/admin/storage/{id}")
    public ApiResponse<Map<String,Object>> storage(@PathVariable long id,HttpServletRequest request){return ApiResponse.success(remote!=null&&remote.isRemote(id)?remote.detail(actor(request),id):storage.storage(actor(request),id));}

    @PostMapping("/admin/storage")
    public ApiResponse<Long> save(@RequestBody Map<String,Object> input,HttpServletRequest request){return ApiResponse.success(remote!=null&&!"local".equalsIgnoreCase(String.valueOf(input.get("type")))?remote.save(actor(request),input):storage.save(actor(request),input));}

    @PostMapping("/admin/storage/test-connection")
    public ApiResponse<Map<String,Object>> testConnection(@RequestBody Map<String,Object> input,HttpServletRequest request){return ApiResponse.success(remote!=null&&!"local".equalsIgnoreCase(String.valueOf(input.get("type")))?remote.testConnection(actor(request),input):storage.testConnection(actor(request),input));}

    @GetMapping("/admin/storage/exist/key")
    public ApiResponse<Boolean> exists(@RequestParam(required=false) String key,@RequestParam(required=false) String storageKey,HttpServletRequest request){
        if(key!=null&&storageKey!=null&&!key.equals(storageKey))throw new ApplicationFault("STORAGE_ALIAS_INVALID",400,"存储别名参数不一致");
        String selected=key==null?storageKey:key;
        if(selected==null||selected.isBlank())throw new ApplicationFault("STORAGE_ALIAS_INVALID",400,"请填写需要检查的存储别名");
        return ApiResponse.success(storage.existsKey(actor(request),selected));
    }

    @PostMapping("/admin/storage/{id}/{action:enable|disable}")
    public ApiResponse<Void> enabled(@PathVariable long id,@PathVariable String action,HttpServletRequest request){if(remote!=null&&remote.isRemote(id))remote.setEnabled(actor(request),id,"enable".equals(action));else storage.setEnabled(actor(request),id,"enable".equals(action));return ApiResponse.success(null);}

    @PostMapping("/admin/cache/{id}/{action:enable|disable}")
    public ApiResponse<Void> cache(@PathVariable long id,@PathVariable String action,HttpServletRequest request){storage.setCache(actor(request),id,"enable".equals(action));return ApiResponse.success(null);}

    @PostMapping("/admin/cache/{id}/auto-refresh/{action:start|stop}")
    public ApiResponse<Void> autoRefresh(@PathVariable long id,@PathVariable String action,HttpServletRequest request){storage.setAutoRefresh(actor(request),id,"start".equals(action));return ApiResponse.success(null);}

    @PostMapping("/admin/storage/sort")
    public ApiResponse<Void> sort(@RequestBody List<Map<String,Object>> order,HttpServletRequest request){storage.sort(actor(request),order);return ApiResponse.success(null);}

    @DeleteMapping("/admin/storage/{id}")
    public ApiResponse<Void> delete(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirmed,HttpServletRequest request){if(remote!=null&&remote.isRemote(id))remote.delete(actor(request),id,confirmed);else storage.delete(actor(request),id,confirmed);return ApiResponse.success(null);}

    @GetMapping("/api/workspace-cloud")
    public ApiResponse<List<Map<String,Object>>> memberClouds(HttpServletRequest request){return ApiResponse.success(storage.workspaceClouds(actor(request)));}

    @GetMapping("/api/workspace-admin/members/{userId}/permissions")
    public ApiResponse<Map<String,Object>> memberPermissions(@PathVariable long userId,HttpServletRequest request){return ApiResponse.success(storage.memberPermissions(actor(request),userId));}

    @PutMapping("/api/workspace-admin/members/{userId}/permissions")
    public ApiResponse<Void> saveMemberPermissions(@PathVariable long userId,@RequestBody MemberPermissionUpdate input,HttpServletRequest request){
        List<StorageAdministrationService.MemberStorageInput> rows=input==null||input.storages()==null?null:input.storages().stream()
                .map(row->row==null?null:new StorageAdministrationService.MemberStorageInput(row.storageSourceId(),row.rootPath(),row.enable(),row.permissions(),row.quotaBytes())).toList();
        storage.saveMemberPermissions(actor(request),userId,input==null?null:new StorageAdministrationService.MemberPermissionsInput(input.defaultPermissions(),rows));
        return ApiResponse.success(null);
    }

    // Compatibility aliases consumed by the existing storage editor's request panel.
    @PostMapping("/api/workspace-storage/local-requests")
    public ApiResponse<LocalStorageRequestView> requestLocal(@Valid @RequestBody LocalStorageRequest input,HttpServletRequest request){return ApiResponse.success(storage.requestLocal(actor(request),input.reason()));}

    @GetMapping("/api/workspace-storage/local-requests")
    public ApiResponse<List<Map<String,Object>>> myLocalRequests(HttpServletRequest request){return ApiResponse.success(cloud.listMyLocalRequests(actor(request)).stream().map(StorageAdministrationController::localRequestView).toList());}

    @GetMapping("/admin/storage-local-requests")
    public ApiResponse<List<Map<String,Object>>> pendingLocalRequests(HttpServletRequest request){return ApiResponse.success(cloud.pendingLocalForGlobalAdmin(actor(request)).stream().map(StorageAdministrationController::localRequestView).toList());}

    @PostMapping("/admin/storage-local-requests/{id}/review")
    public ApiResponse<LocalStorageRequestView> reviewLocalRequest(@PathVariable long id,@RequestBody LocalStorageReview input,HttpServletRequest request){
        Actor actor=actor(request);return ApiResponse.success(input.approve()?cloud.approveLocal(actor,id):cloud.rejectLocal(actor,id,input.note()));
    }

    private static Actor actor(HttpServletRequest request){return AuthenticationFilter.actor(request);}
    private static Map<String,Object> localRequestView(LocalStorageRequestView row){var result=new LinkedHashMap<String,Object>();
        result.put("id",row.id());result.put("workspaceId",row.workspaceId());result.put("tenantId",row.workspaceId());result.put("tenantName","工作空间 "+row.workspaceId());
        result.put("requesterId",row.requesterId());result.put("name",row.name());result.put("status",row.status());result.put("reason",row.reason());result.put("reviewNote","REJECTED".equals(row.status())?row.reason():null);
        result.put("mountId",row.mountId());result.put("createdAt",row.createdAt());result.put("decidedAt",row.decidedAt());return result;}
}
