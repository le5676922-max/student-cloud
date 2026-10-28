package io.studentcloud.content.http;

import io.studentcloud.common.*;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.application.*;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drives/{key}")
public final class FileLifecycleController {
    private final DriveOperations mounts;
    public FileLifecycleController(DriveOperations mounts){this.mounts=mounts;}
    @GetMapping("/catalog") public ApiResponse<FileCatalog.Page> page(@PathVariable String key,@RequestParam(defaultValue="/") String path,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(defaultValue="") String query,
        @RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return ApiResponse.success(mounts.catalogPage(AuthenticationFilter.actor(request),key,DrivePath.of(path),page,pageSize,query,refresh));
    }
    @GetMapping("/recycle-bin") public ApiResponse<List<MountService.TrashView>> list(@PathVariable String key,HttpServletRequest request){return ApiResponse.success(mounts.recycleBin(AuthenticationFilter.actor(request),key));}
    @PostMapping("/recycle-bin/{id}/restore") public ApiResponse<Void> restore(@PathVariable String key,@PathVariable String id,HttpServletRequest request){mounts.restoreRecycled(AuthenticationFilter.actor(request),key,id);return ApiResponse.success(null);}
    @DeleteMapping("/recycle-bin/{id}") public ApiResponse<Void> purge(@PathVariable String key,@PathVariable String id,@RequestParam(defaultValue="false") boolean confirmed,HttpServletRequest request){
        if(!confirmed)throw new ApplicationFault("TRASH_CONFIRM_REQUIRED",400,"请明确确认彻底删除，文件将无法恢复");
        mounts.purgeRecycled(AuthenticationFilter.actor(request),key,id);return ApiResponse.success(null);
    }
}
