package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.drive.application.DriveOperations;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** Browser contract over the independent local-storage use cases. All paths are grant-relative. */
@RestController
public class FileBrowserController {
    private static final DateTimeFormatter DISPLAY_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private final DriveOperations mounts;
    public FileBrowserController(DriveOperations mounts) { this.mounts = mounts; }
    public record StorageType(String key, String description) { }
    public record StorageView(long id, String key, String name, StorageType type) { }
    public record DirectoryInput(@Size(max=64) String storageKey, @Size(max=64) String key,
                                 @Size(max=4096) String path,Boolean refreshMetadata) { }
    public record ItemInput(@NotBlank @Size(max=64) String storageKey, @Size(max=4096) String path,
                            @Size(max=255) String name) { }
    public record ChangeInput(@NotBlank @Size(max=64) String storageKey, @Size(max=4096) String path,
                              @Size(max=4096) String parent, @NotBlank @Size(max=255) String name,
                              @Size(max=255) String newName) { }
    public record TransferInput(@NotBlank @Size(max=64) String storageKey, @Size(max=4096) String path,
                                @NotNull @Size(min=1,max=20) List<@NotBlank @Size(max=255) String> nameList,
                                @NotNull @Size(max=4096) String targetPath,
                                @NotNull @Size(min=1,max=20) List<@NotBlank @Size(max=255) String> targetNameList) { }
    public record DeleteItem(@Size(max=4096) String path, @NotBlank @Size(max=255) String name) { }
    public record DeleteInput(@NotBlank @Size(max=64) String storageKey,
                              @NotNull @Size(min=1,max=20) List<@NotNull @Valid DeleteItem> deleteItems) { }
    public record UploadInput(@NotBlank @Size(max=64) String storageKey, @Size(max=4096) String path,
                              @NotBlank @Size(max=255) String name, @NotNull Long size) { }
    public record BrowserEntry(String type, String name, String path, long size, String time, long modifiedAt,
                                String url, boolean authenticatedContent, boolean previewAvailable, String previewUrl, String previewKind) {
        static BrowserEntry of(String key, FileRecord file) {
            var preview=PreviewController.format(file);
            boolean available=preview!=null&&file.size()>=0&&file.size()<=preview.limit();
            return new BrowserEntry(file.directory() ? "FOLDER" : "FILE", file.name(),
                    file.path().parent().value(), file.size(), DISPLAY_TIME.format(file.modifiedAt()), file.modifiedAt().toEpochMilli(),
                    file.directory() ? null : fileUrl(key, "content", file.path()), !file.directory(),available,
                    available?fileUrl(key,"preview",file.path()):null,available?preview.kind():null);
        }
    }
    public record BatchResult(String name, String path, boolean success, String message) { }

    @GetMapping("/api/storage/list")
    public ApiResponse<List<StorageView>> sources(HttpServletRequest request) {
        return ApiResponse.success(mounts.listMounts(AuthenticationFilter.actor(request)).stream()
                .filter(m -> m.enabled())
                .map(m -> new StorageView(m.id(), m.key(), m.name(), new StorageType("LOCAL".equals(m.engine())?"INDEPENDENT_LOCAL":"INDEPENDENT_REMOTE", "LOCAL".equals(m.engine())?"本地存储":"对象存储"))).toList());
    }

    @PostMapping("/api/storage/files")
    public ApiResponse<Map<String,Object>> files(@Valid @RequestBody DirectoryInput input, HttpServletRequest request) {
        String key = key(input);
        var files = mounts.list(AuthenticationFilter.actor(request), key, virtual(input.path()),Boolean.TRUE.equals(input.refreshMetadata())).stream()
                .map(file -> BrowserEntry.of(key, file)).toList();
        return ApiResponse.success(Map.of("files", files));
    }

    @PostMapping("/api/storage/file/item")
    public ApiResponse<BrowserEntry> item(@Valid @RequestBody ItemInput input, HttpServletRequest request) {
        DrivePath path = input.name() == null ? virtual(input.path()) : child(virtual(input.path()), input.name());
        FileRecord item = mounts.stat(AuthenticationFilter.actor(request), input.storageKey(), path)
                .orElseThrow(() -> new ApplicationFault("DRIVE_NOT_FOUND", 404, "文件或目录不存在"));
        return ApiResponse.success(BrowserEntry.of(input.storageKey(), item));
    }

    @PostMapping("/api/site/config/storage")
    public ApiResponse<Map<String,Object>> configuration(@Valid @RequestBody DirectoryInput input, HttpServletRequest request) {
        Set<String> operations = mounts.directoryCapabilities(AuthenticationFilter.actor(request), key(input), virtual(input.path()));
        Map<String,Boolean> permission = new LinkedHashMap<>();
        for (String disabled : List.of("copyDownloadLink", "share", "createShareLink", "customShareKey",
                "generateLink", "generateShortLink", "directLink", "shortLink", "batchDownload", "packageDownload", "search"))
            permission.put(disabled, false);
        permission.put("list", operations.contains("list"));
        permission.put("download", operations.contains("read"));
        permission.put("preview", operations.contains("read"));
        permission.put("newFolder", operations.contains("mkdir"));
        permission.put("upload", operations.contains("upload"));
        permission.put("copy", operations.containsAll(Set.of("read", "copy")));
        for (String operation : List.of("move", "rename", "delete")) permission.put(operation, operations.contains(operation));
        boolean local=mounts.listMounts(AuthenticationFilter.actor(request)).stream().anyMatch(m->m.key().equals(key(input))&&m.engine().equals("LOCAL"));
        permission.put("createShareLink",local && operations.contains("read") && AuthenticationFilter.actor(request).workspaceId()!=null);
        Map<String,Object> metadata = Map.of("uploadType", "PROXY", "needCreateFolderBeforeUpload", true,"controlledShareFilesOnly",true,"recycleBin",local,
                "supportRenameFolder", permission.get("rename"), "supportMoveFolder", permission.get("move"),
                "supportCopyFolder", permission.get("copy"), "supportDeleteNotEmptyFolder", permission.get("delete"));
        return ApiResponse.success(Map.of("rootPath", "/", "permission", permission, "metadata", metadata,
                "proxyUpload", true, "readmeText", ""));
    }

    @GetMapping("/api/site/config/userRootPath/{key}")
    public ApiResponse<String> userRoot(@PathVariable String key, HttpServletRequest request) {
        mounts.directoryCapabilities(AuthenticationFilter.actor(request), key, DrivePath.of("/"));
        return ApiResponse.success("/");
    }

    @PostMapping("/api/file/operator/mkdir")
    public ApiResponse<BrowserEntry> mkdir(@Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        return ApiResponse.success(BrowserEntry.of(input.storageKey(), mounts.mkdir(AuthenticationFilter.actor(request),
                input.storageKey(), child(parent(input), input.name()))));
    }

    @PostMapping({"/api/file/operator/rename/file", "/api/file/operator/rename/folder"})
    public ApiResponse<BrowserEntry> rename(@Valid @RequestBody ChangeInput input, HttpServletRequest request) {
        // Validate the target segment before dispatch; the core still owns mutation and authorization.
        DrivePath source = child(parent(input), input.name());
        child(source.parent(), input.newName());
        return ApiResponse.success(BrowserEntry.of(input.storageKey(), mounts.rename(AuthenticationFilter.actor(request),
                input.storageKey(), source, input.newName())));
    }

    @PostMapping({"/api/file/operator/copy/file", "/api/file/operator/copy/folder"})
    public ApiResponse<List<BatchResult>> copy(@Valid @RequestBody TransferInput input, HttpServletRequest request) {
        return transfer(input, request, false);
    }

    @PostMapping({"/api/file/operator/move/file", "/api/file/operator/move/folder"})
    public ApiResponse<List<BatchResult>> move(@Valid @RequestBody TransferInput input, HttpServletRequest request) {
        return transfer(input, request, true);
    }

    private ApiResponse<List<BatchResult>> transfer(TransferInput input, HttpServletRequest request, boolean move) {
        if (input.nameList().size() != input.targetNameList().size()) throw invalid();
        DrivePath parent = virtual(input.path());
        DrivePath targetParent = virtual(input.targetPath());
        // Syntax errors reject the request before any item is mutated. Runtime failures remain per-item.
        List<DrivePath> sources = input.nameList().stream().map(name -> child(parent, name)).toList();
        List<DrivePath> targets = input.targetNameList().stream().map(name -> child(targetParent, name)).toList();
        var actor = AuthenticationFilter.actor(request);
        List<BatchResult> results = new ArrayList<>();
        for (int i = 0; i < input.nameList().size(); i++) {
            String name = input.nameList().get(i);
            try {
                DrivePath source = sources.get(i);
                DrivePath target = targets.get(i);
                if (move) mounts.move(actor, input.storageKey(), source, target);
                else mounts.copy(actor, input.storageKey(), source, target);
                results.add(new BatchResult(name, parent.value(), true, "ok"));
            } catch (ApplicationFault failure) {
                results.add(new BatchResult(name, parent.value(), false, failure.getMessage()));
            }
        }
        return ApiResponse.success(List.copyOf(results));
    }

    @PostMapping("/api/file/operator/delete/batch")
    public ApiResponse<List<BatchResult>> delete(@Valid @RequestBody DeleteInput input, HttpServletRequest request) {
        var actor = AuthenticationFilter.actor(request);
        List<DrivePath> targets = input.deleteItems().stream()
                .map(item -> child(virtual(item.path()), item.name())).toList();
        List<BatchResult> results = new ArrayList<>();
        for (int index=0;index<input.deleteItems().size();index++) {
            DeleteItem item=input.deleteItems().get(index);
            DrivePath target=targets.get(index);
            try {
                mounts.delete(actor, input.storageKey(), target);
                results.add(new BatchResult(item.name(), target.parent().value(), true, "ok"));
            } catch (ApplicationFault failure) {
                results.add(new BatchResult(item.name(), item.path(), false, failure.getMessage()));
            }
        }
        return ApiResponse.success(List.copyOf(results));
    }

    @PostMapping("/api/file/operator/upload/file")
    public ApiResponse<String> upload(@Valid @RequestBody UploadInput input, HttpServletRequest request) {
        DrivePath target = prepareUpload(input, request);
        return ApiResponse.success(fileUrl(input.storageKey(), "files", target));
    }

    private DrivePath prepareUpload(UploadInput input, HttpServletRequest request) {
        DrivePath target = child(virtual(input.path()), input.name());
        mounts.prepareUpload(AuthenticationFilter.actor(request), input.storageKey(), target, input.size());
        return target;
    }

    private static String fileUrl(String key, String endpoint, DrivePath path) {
        return "/api/drives/" + key + "/" + endpoint + "?path="
                + URLEncoder.encode(path.value(), StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String key(DirectoryInput input) {
        if (input.storageKey() != null && input.key() != null && !input.storageKey().equals(input.key())) throw invalid();
        String key = input.storageKey() == null ? input.key() : input.storageKey();
        if (key == null || key.isBlank()) throw invalid();
        return key;
    }
    private static DrivePath parent(ChangeInput input) {
        if (input.path() != null && input.parent() != null
                && !virtual(input.path()).equals(virtual(input.parent()))) throw invalid();
        return virtual(input.path() == null ? input.parent() : input.path());
    }
    private static DrivePath virtual(String path) {
        try { return DrivePath.of(path == null || path.isEmpty() ? "/" : path); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static DrivePath child(DrivePath parent, String name) {
        try { return parent.child(name); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static ApplicationFault invalid() { return new ApplicationFault("DRIVE_INVALID", 400, "路径或操作参数无效"); }
}
