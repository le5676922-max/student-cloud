package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.drive.application.StorageOperationJournal;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Owner-only, bounded receipts. No browser endpoint can assert success or replay an unknown write. */
@RestController
public class StorageOperationController {
    private final StorageOperationJournal journal;
    public StorageOperationController(StorageOperationJournal journal){this.journal=journal;}
    @GetMapping("/api/drives/{key}/operations/{id}")
    public ResponseEntity<ApiResponse<StorageOperationJournal.Receipt>> get(@PathVariable String key,@PathVariable String id,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(journal.get(AuthenticationFilter.actor(request),key,id)));
    }
    @GetMapping("/api/drives/{key}/operations")
    public ResponseEntity<ApiResponse<StorageOperationJournal.Page>> list(@PathVariable String key,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(journal.list(AuthenticationFilter.actor(request),key,cursor,limit)));
    }
}
