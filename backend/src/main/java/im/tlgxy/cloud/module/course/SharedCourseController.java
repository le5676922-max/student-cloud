package im.tlgxy.cloud.module.course;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("/api/courses")
public class SharedCourseController {
    private final SharedCourseService service;
    private int actor() { return TlgxyCloudAuthUtil.getCurrentUserId(); }
    public record Join(String code) { }
    public record Edit(String name, String description, long quotaBytes, boolean enabled) { }
    public record Review(boolean approve) { }
    public record InvitationDraft(Long expiresAt, Integer maxMembers) { }

    @GetMapping public AjaxJson<List<SharedCourseService.Course>> list() { return AjaxJson.getSuccessData(service.list(actor())); }
    @GetMapping("/{id}") public AjaxJson<SharedCourseService.Course> get(@PathVariable String id) { return AjaxJson.getSuccessData(service.get(actor(), id)); }
    @PostMapping @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<SharedCourseService.Created> create(@RequestBody SharedCourseService.Draft draft) { return AjaxJson.getSuccessData(service.create(actor(), draft)); }
    @PutMapping("/{id}") @DemoDisable @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<SharedCourseService.Course> edit(@PathVariable String id, @RequestBody Edit draft) {
        if (draft == null) throw new BizException("请填写课程信息");
        return AjaxJson.getSuccessData(service.edit(actor(), id, new SharedCourseService.Draft(draft.name(), draft.description(), draft.quotaBytes()), draft.enabled()));
    }
    @PostMapping("/{id}/code") @DemoDisable @ApiLimit(timeout=60,maxCount=5)
    public AjaxJson<String> code(@PathVariable String id) { return AjaxJson.getSuccessData(service.rotateCode(actor(), id)); }
    @PostMapping("/{id}/invitations") @DemoDisable @ApiLimit(timeout=60,maxCount=5)
    public AjaxJson<SharedCourseService.CreatedInvitation> createInvitation(@PathVariable String id, @RequestBody(required=false) InvitationDraft request) {
        return AjaxJson.getSuccessData(service.createInvitation(actor(), id, request == null ? null : request.expiresAt(), request == null ? null : request.maxMembers()));
    }
    @GetMapping("/{id}/invitations") @ApiLimit(timeout=60,maxCount=30)
    public AjaxJson<List<SharedCourseService.Invitation>> invitations(@PathVariable String id,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="20") int limit) {
        return AjaxJson.getSuccessData(service.invitations(actor(), id, offset, limit));
    }
    @DeleteMapping("/{id}/invitations/{invitationId}") @DemoDisable @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<Void> revokeInvitation(@PathVariable String id, @PathVariable String invitationId) {
        service.revokeInvitation(actor(), id, invitationId); return AjaxJson.getSuccess();
    }
    @PostMapping("/invitation-preview") @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<SharedCourseService.InvitationPreview> previewInvitation(@RequestBody Join request) {
        return AjaxJson.getSuccessData(service.previewInvitation(actor(), request == null ? null : request.code()));
    }
    @PostMapping("/join") @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<SharedCourseService.Course> join(@RequestBody Join request) {
        return AjaxJson.getSuccessData(service.join(actor(), request == null ? null : request.code()));
    }
    @PostMapping("/{id}/leave") @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<Void> leave(@PathVariable String id) { service.leave(actor(), id); return AjaxJson.getSuccess(); }
    @PostMapping("/{id}/upload-request") @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<Void> requestUpload(@PathVariable String id) { service.requestUpload(actor(), id); return AjaxJson.getSuccess(); }
    @GetMapping("/{id}/members")
    public AjaxJson<List<SharedCourseService.Member>> members(@PathVariable String id) { return AjaxJson.getSuccessData(service.members(actor(), id)); }
    @PostMapping("/{id}/members/{memberId}/upload") @DemoDisable @ApiLimit(timeout=60,maxCount=30)
    public AjaxJson<Void> review(@PathVariable String id, @PathVariable int memberId, @RequestBody Review request) {
        if (request == null) throw new BizException("请选择审批结果");
        service.reviewUpload(actor(), id, memberId, request.approve()); return AjaxJson.getSuccess();
    }
    @DeleteMapping("/{id}/members/{memberId}") @DemoDisable @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<Void> remove(@PathVariable String id, @PathVariable int memberId) { service.removeMember(actor(), id, memberId); return AjaxJson.getSuccess(); }
    @GetMapping("/{id}/materials")
    public AjaxJson<List<SharedCourseService.Material>> materials(@PathVariable String id) { return AjaxJson.getSuccessData(service.materials(actor(), id)); }
    @PostMapping(value="/{id}/materials",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @DemoDisable @ApiLimit(timeout=60,maxCount=10)
    public AjaxJson<SharedCourseService.Material> upload(@PathVariable String id, @RequestParam MultipartFile file) {
        if (file.isEmpty() || file.getSize() > CourseFileStore.MAX_FILE_BYTES) throw new BizException("课程资料大小须在 1 字节至 50 MB 之间");
        try (var input = file.getInputStream()) { return AjaxJson.getSuccessData(service.upload(actor(), id, file.getOriginalFilename(), input)); }
        catch (IOException exception) { throw new BizException("无法读取上传文件，请重试"); }
    }
    @GetMapping("/{id}/materials/{materialId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id, @PathVariable String materialId) {
        var result = service.download(actor(), id, materialId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(result.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(result.resource());
    }
    @DeleteMapping("/{id}/materials/{materialId}") @DemoDisable @ApiLimit(timeout=60,maxCount=20)
    public AjaxJson<Void> delete(@PathVariable String id, @PathVariable String materialId) { service.deleteMaterial(actor(), id, materialId); return AjaxJson.getSuccess(); }
    @GetMapping("/{id}/incomplete-uploads")
    public AjaxJson<List<SharedCourseService.Material>> incomplete(@PathVariable String id) { return AjaxJson.getSuccessData(service.incompleteUploads(actor(), id)); }
}
