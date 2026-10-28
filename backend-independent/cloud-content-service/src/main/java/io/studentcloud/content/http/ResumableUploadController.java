package io.studentcloud.content.http;

import io.studentcloud.common.*;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.application.ResumableUploadService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Strict header authentication is supplied by AuthenticationFilter, never URL credentials. */
@RestController
@RequestMapping("/api/uploads/resumable")
public class ResumableUploadController {
    private final ResumableUploadService uploads;
    public ResumableUploadController(ResumableUploadService uploads){this.uploads=uploads;}
    public record Input(@NotBlank @Size(max=64) String storageKey,@Size(max=4096) String path,
                        @NotBlank @Size(max=255) String name,@NotNull @Min(1) Long size,
                        @Size(max=13) List<@Pattern(regexp="[a-f0-9]{64}") String> chunkHashes,
                        @Size(max=64) String manifestHash) { }
    @PostMapping("/capabilities")
    public ApiResponse<Map<String,Object>> capabilities(@Valid @RequestBody Input input,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(uploads.capabilities(AuthenticationFilter.actor(request),input.storageKey(),target(input),input.size()));
    }
    @PostMapping("/init")
    public ApiResponse<ResumableUploadService.View> init(@Valid @RequestBody Input input,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(uploads.init(AuthenticationFilter.actor(request),input.storageKey(),target(input),input.size(),input.chunkHashes(),input.manifestHash()));
    }
    @GetMapping("/{id}")
    public ApiResponse<ResumableUploadService.View> status(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(uploads.status(AuthenticationFilter.actor(request),id));
    }
    @PutMapping(value="/{id}/chunks/{index}",consumes="application/octet-stream")
    public ApiResponse<ResumableUploadService.View> chunk(@PathVariable String id,@PathVariable int index,
            @RequestHeader("X-Upload-Chunk-SHA256") String hash,HttpServletRequest request,HttpServletResponse response)throws IOException{
        response.setHeader("Cache-Control","no-store");
        long length=request.getContentLengthLong();
        if(length>ResumableUploadService.CHUNK_SIZE)throw new ApplicationFault("UPLOAD_TOO_LARGE",413,"分片正文不能超过4MiB");
        return ApiResponse.success(uploads.chunk(AuthenticationFilter.actor(request),id,index,hash,request.getInputStream()));
    }
    @PostMapping("/{id}/complete")
    public ApiResponse<ResumableUploadService.View> complete(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(uploads.complete(AuthenticationFilter.actor(request),id));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<ResumableUploadService.View> cancel(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return ApiResponse.success(uploads.cancel(AuthenticationFilter.actor(request),id));
    }
    private static DrivePath target(Input input){try{return DrivePath.of(input.path()==null||input.path().isBlank()?"/":input.path()).child(input.name());}
        catch(IllegalArgumentException invalid){throw new ApplicationFault("UPLOAD_INVALID",400,"上传路径或名称无效");}}
}
