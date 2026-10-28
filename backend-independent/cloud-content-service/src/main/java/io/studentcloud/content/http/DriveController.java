package io.studentcloud.content.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import io.studentcloud.drive.application.MountView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Independently implemented storage HTTP adapter. Legacy UI adapters can reuse these use cases. */
@RestController
public class DriveController {
    private final DriveOperations mounts;
    private final MountService localMounts;
    @org.springframework.beans.factory.annotation.Autowired public DriveController(DriveOperations mounts,MountService localMounts) { this.mounts = mounts;this.localMounts=localMounts; }
    public DriveController(MountService mounts){this(mounts,mounts);}

    public record LocalMountInput(@NotBlank @Size(max=120) String name, @Size(max=64) String key,
                                  @NotBlank @Size(max=2048) String physicalRoot, Long workspaceId) { }
    public record ChangeInput(@NotBlank @Size(max=1024) String path, @Size(max=1024) String targetPath, @Size(max=255) String newName) { }

    @GetMapping("/api/drives")
    public ApiResponse<List<MountView>> sources(HttpServletRequest request) {
        return ApiResponse.success(mounts.listMounts(AuthenticationFilter.actor(request)));
    }

    @PostMapping("/api/drives/local")
    public ApiResponse<MountView> local(@Valid @RequestBody LocalMountInput input, HttpServletRequest request) {
        String key = input.key() == null || input.key().isBlank() ? "drive-" + UUID.randomUUID().toString().replace("-", "") : input.key();
        Path root;
        try { root = Path.of(input.physicalRoot()); }
        catch (RuntimeException invalid) { throw new ApplicationFault("41000", 400, "请选择受控范围内的目录"); }
        return ApiResponse.success(localMounts.createLocal(AuthenticationFilter.actor(request), input.name(), key, root, input.workspaceId()));
    }

    @GetMapping("/api/drives/{key}/entries")
    public ApiResponse<List<EntryView>> entries(@PathVariable String key, @RequestParam(defaultValue="/") String path, HttpServletRequest request) {
        return ApiResponse.success(mounts.list(AuthenticationFilter.actor(request), key, virtual(path)).stream().map(EntryView::of).toList());
    }
    @GetMapping("/api/drives/{key}/metadata")
    public ApiResponse<EntryView> metadata(@PathVariable String key, @RequestParam String path, HttpServletRequest request) {
        return ApiResponse.success(EntryView.of(mounts.stat(AuthenticationFilter.actor(request), key, virtual(path))
                .orElseThrow(() -> new ApplicationFault("DRIVE_NOT_FOUND", 404, "文件或目录不存在"))));
    }
    @PostMapping("/api/drives/{key}/directories")
    public ApiResponse<EntryView> directory(@PathVariable String key, @Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        return ApiResponse.success(EntryView.of(mounts.mkdir(AuthenticationFilter.actor(request), key, virtual(input.path()))));
    }
    @RequestMapping(value="/api/drives/{key}/files", method={RequestMethod.POST, RequestMethod.PUT})
    public ApiResponse<EntryView> upload(@PathVariable String key, @RequestParam String path,
            @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        try (var input = file.getInputStream()) {
            return ApiResponse.success(EntryView.of(mounts.upload(AuthenticationFilter.actor(request), key, virtual(path), input, file.getSize())));
        }
    }
    @GetMapping("/api/drives/{key}/content")
    public ResponseEntity<Resource> content(@PathVariable String key, @RequestParam String path, HttpServletRequest request) throws IOException {
        var file = mounts.readWithMetadata(AuthenticationFilter.actor(request), key, virtual(path));
        try {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(file.metadata().name(), StandardCharsets.UTF_8).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.metadata().size()).body(new InputStreamResource(file.input()));
        } catch (RuntimeException failure) { file.close(); throw failure; }
    }
    @PostMapping("/api/drives/{key}/copy")
    public ApiResponse<EntryView> copy(@PathVariable String key, @Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        return ApiResponse.success(EntryView.of(mounts.copy(AuthenticationFilter.actor(request), key, virtual(input.path()), virtual(input.targetPath()))));
    }
    @PostMapping("/api/drives/{key}/move")
    public ApiResponse<EntryView> move(@PathVariable String key, @Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        return ApiResponse.success(EntryView.of(mounts.move(AuthenticationFilter.actor(request), key, virtual(input.path()), virtual(input.targetPath()))));
    }
    @PostMapping("/api/drives/{key}/rename")
    public ApiResponse<EntryView> rename(@PathVariable String key, @Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        return ApiResponse.success(EntryView.of(mounts.rename(AuthenticationFilter.actor(request), key, virtual(input.path()), input.newName())));
    }
    @DeleteMapping("/api/drives/{key}/entries")
    public ApiResponse<Void> delete(@PathVariable String key, @RequestParam String path, HttpServletRequest request) {
        mounts.delete(AuthenticationFilter.actor(request), key, virtual(path)); return ApiResponse.success(null);
    }

    private static DrivePath virtual(String raw) {
        try { return DrivePath.of(raw); }
        catch (RuntimeException invalid) { throw new ApplicationFault("41000", 400, "文件路径无效"); }
    }
    public record EntryView(String path, String name, boolean directory, long size, long modifiedAt) {
        static EntryView of(FileRecord item) { return new EntryView(item.path().value(), item.name(), item.directory(), item.size(), item.modifiedAt().toEpochMilli()); }
    }
}
