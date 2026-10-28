package io.studentcloud.content.http;

import io.studentcloud.runtime.http.AuthenticationFilter;

import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.course.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final SharedCourseService courses;
    private final CourseMaterialService materials;
    public CourseController(SharedCourseService courses, CourseMaterialService materials) { this.courses = courses; this.materials = materials; }

    public record CourseInput(@NotBlank @Size(max=120) String name, @Size(max=2000) String description, long quotaBytes, boolean enabled) { }
    public record InvitationInput(Long expiresAt, Integer maxMembers) { }
    public record CodeInput(@NotBlank @Size(max=64) String code) { }
    public record ApprovalInput(boolean approve) { }

    @GetMapping public ApiResponse<List<Map<String,Object>>> list(HttpServletRequest request) {
        return ApiResponse.success(courses.list(AuthenticationFilter.actor(request)).stream().map(CourseController::view).toList());
    }
    @PostMapping public ApiResponse<Map<String,Object>> create(@Valid @RequestBody CourseInput input, HttpServletRequest request) {
        return ApiResponse.success(view(courses.create(AuthenticationFilter.actor(request), input.name(), input.description(), input.quotaBytes(), input.enabled())));
    }
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(view(courses.get(AuthenticationFilter.actor(request), id)));
    }
    @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id, @Valid @RequestBody CourseInput input, HttpServletRequest request) {
        return ApiResponse.success(view(courses.update(AuthenticationFilter.actor(request), id, input.name(), input.description(), input.quotaBytes(), input.enabled())));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        courses.delete(AuthenticationFilter.actor(request), id); return ApiResponse.success(null);
    }
    @PostMapping("/invitation-preview") public ApiResponse<InvitationPreview> preview(@Valid @RequestBody CodeInput input, HttpServletRequest request) {
        return ApiResponse.success(courses.previewInvitation(AuthenticationFilter.actor(request), input.code()));
    }
    @PostMapping("/join") public ApiResponse<Map<String,Object>> join(@Valid @RequestBody CodeInput input, HttpServletRequest request) {
        return ApiResponse.success(view(courses.join(AuthenticationFilter.actor(request), input.code())));
    }
    @GetMapping("/{id}/invitations") public ApiResponse<List<InviteView>> invitations(@PathVariable long id,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        return ApiResponse.success(courses.invitations(AuthenticationFilter.actor(request), id, offset, limit));
    }
    @PostMapping("/{id}/invitations") public ApiResponse<CreatedInvitation> invite(@PathVariable long id, @RequestBody InvitationInput input, HttpServletRequest request) {
        return ApiResponse.success(courses.createInvitation(AuthenticationFilter.actor(request), id, input.expiresAt(), input.maxMembers()));
    }
    @DeleteMapping("/{id}/invitations/{inviteId}") public ApiResponse<Void> revoke(@PathVariable long id, @PathVariable long inviteId, HttpServletRequest request) {
        courses.revokeInvitation(AuthenticationFilter.actor(request), id, inviteId); return ApiResponse.success(null);
    }
    @PostMapping("/{id}/leave") public ApiResponse<Void> leave(@PathVariable long id, HttpServletRequest request) {
        courses.leave(AuthenticationFilter.actor(request), id); return ApiResponse.success(null);
    }
    @GetMapping("/{id}/members") public ApiResponse<List<MemberView>> members(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(courses.members(AuthenticationFilter.actor(request), id));
    }
    @PostMapping("/{id}/upload-request") public ApiResponse<Void> requestUpload(@PathVariable long id, HttpServletRequest request) {
        courses.requestUpload(AuthenticationFilter.actor(request), id); return ApiResponse.success(null);
    }
    @PostMapping("/{id}/members/{userId}/upload") public ApiResponse<Void> review(@PathVariable long id, @PathVariable long userId,
            @RequestBody ApprovalInput input, HttpServletRequest request) {
        courses.reviewUpload(AuthenticationFilter.actor(request), id, userId, input.approve()); return ApiResponse.success(null);
    }
    @DeleteMapping("/{id}/members/{userId}") public ApiResponse<Void> removeMember(@PathVariable long id, @PathVariable long userId, HttpServletRequest request) {
        courses.removeMember(AuthenticationFilter.actor(request), id, userId); return ApiResponse.success(null);
    }
    @GetMapping("/{id}/materials") public ApiResponse<List<MaterialView>> materials(@PathVariable long id, HttpServletRequest request) throws IOException {
        return ApiResponse.success(materials.list(AuthenticationFilter.actor(request), id));
    }
    @GetMapping("/{id}/incomplete-uploads") public ApiResponse<List<MaterialView>> incomplete(@PathVariable long id, HttpServletRequest request) throws IOException {
        return ApiResponse.success(materials.incomplete(AuthenticationFilter.actor(request), id));
    }
    @PostMapping("/{id}/materials") public ApiResponse<MaterialView> upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        if (file.getOriginalFilename() == null) throw new ApplicationFault("41000", 400, "请选择文件");
        try (var input = file.getInputStream()) {
            return ApiResponse.success(materials.upload(AuthenticationFilter.actor(request), id, file.getOriginalFilename(), input, file.getSize()));
        }
    }
    @GetMapping("/{id}/materials/{materialId}/download") public ResponseEntity<Resource> download(@PathVariable long id,
            @PathVariable long materialId, HttpServletRequest request) throws IOException {
        MaterialDownload file = materials.download(AuthenticationFilter.actor(request), id, materialId);
        try {
            String disposition = ContentDisposition.attachment().filename(file.material().filename(), StandardCharsets.UTF_8).build().toString();
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.material().sizeBytes()).body(new InputStreamResource(file.content()));
        } catch (RuntimeException failure) { file.close(); throw failure; }
    }
    @DeleteMapping("/{id}/materials/{materialId}") public ApiResponse<Void> deleteMaterial(@PathVariable long id,
            @PathVariable long materialId, HttpServletRequest request) throws IOException {
        materials.delete(AuthenticationFilter.actor(request), id, materialId); return ApiResponse.success(null);
    }

    private static Map<String,Object> view(CourseView course) {
        var data = new LinkedHashMap<String,Object>();
        data.put("id", Long.toString(course.id())); data.put("name", course.name()); data.put("description", course.description());
        data.put("quotaBytes", course.quotaBytes()); data.put("usedBytes", course.usedBytes()); data.put("materialCount", course.materialCount());
        data.put("memberCount", course.memberCount()); data.put("enabled", course.enabled()); data.put("creator", course.creator());
        data.put("uploadStatus", course.uploadStatus()); data.put("createdAt", course.createdAt()); data.put("updatedAt", course.updatedAt());
        return data;
    }
}
