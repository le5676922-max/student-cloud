package io.studentcloud.content.http;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.content.sharing.ControlledShareService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shares")
public final class ControlledShareController {
    private final ControlledShareService shares;
    public ControlledShareController(ControlledShareService shares){this.shares=shares;}
    public record CreateInput(@NotBlank @Size(max=64) String storageKey,@NotBlank @Size(max=4096) String path,
                              @NotBlank @Pattern(regexp="OWN_WORKSPACE|COURSE") String audience,Long courseId,Long expiresAt){}
    @PostMapping public ApiResponse<ControlledShareService.ShareView> create(@Valid @RequestBody CreateInput input,HttpServletRequest request){
        return ApiResponse.success(shares.create(AuthenticationFilter.actor(request),input.storageKey(),input.path(),input.audience(),input.courseId(),input.expiresAt()));
    }
    @GetMapping public ApiResponse<List<ControlledShareService.ShareView>> list(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,HttpServletRequest request,HttpServletResponse response){
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return ApiResponse.success(shares.list(AuthenticationFilter.actor(request),offset,limit));
    }
    @GetMapping("/{id}") public ApiResponse<ControlledShareService.ShareView> get(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");return ApiResponse.success(shares.get(AuthenticationFilter.actor(request),id));}
    @DeleteMapping("/{id}") public ApiResponse<Void> revoke(@PathVariable String id,@RequestParam long version,HttpServletRequest request){shares.revoke(AuthenticationFilter.actor(request),id,version);return ApiResponse.success(null);}
    @GetMapping("/{id}/content") public void content(@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        try(var file=shares.open(AuthenticationFilter.actor(request),id)){
            response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Security-Policy","default-src 'none'; sandbox");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString());
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);response.setContentLengthLong(file.size());
            file.input().transferTo(response.getOutputStream());response.flushBuffer();
        }
    }
}
