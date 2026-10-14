package im.tlgxy.cloud.module.storage.controller;

import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.core.annotation.DemoDisable;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadCapabilitiesRequest;
import im.tlgxy.cloud.module.storage.model.request.ResumableUploadInitRequest;
import im.tlgxy.cloud.module.storage.service.LocalResumableUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/uploads/resumable")
public class LocalResumableUploadController {
    private static final String PASSWORD_HEADER = "X-Upload-Directory-Password";
    private final LocalResumableUploadService uploads;

    @PostMapping("/capabilities")
    @ApiLimit(timeout = 60, maxCount = 120)
    public AjaxJson<LocalResumableUploadService.Capabilities> capabilities(@Valid @RequestBody ResumableUploadCapabilitiesRequest request,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return AjaxJson.getSuccessData(uploads.capabilities(TlgxyCloudAuthUtil.getCurrentUserId(), request, decodePassword(password)));
    }

    @PostMapping("/init")
    @ApiLimit(timeout = 60, maxCount = 120)
    @DemoDisable
    public AjaxJson<LocalResumableUploadService.SessionView> initialize(@Valid @RequestBody ResumableUploadInitRequest request,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return AjaxJson.getSuccessData(uploads.initialize(TlgxyCloudAuthUtil.getCurrentUserId(), request, decodePassword(password)));
    }

    @GetMapping("/{id}")
    @ApiLimit(timeout = 60, maxCount = 300)
    public AjaxJson<LocalResumableUploadService.SessionView> status(@PathVariable String id,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return AjaxJson.getSuccessData(uploads.status(TlgxyCloudAuthUtil.getCurrentUserId(), id, decodePassword(password)));
    }

    @PutMapping(value = "/{id}/chunks/{index}", consumes = "application/octet-stream")
    @ApiLimit(timeout = 60, maxCount = 3000)
    @DemoDisable
    public AjaxJson<LocalResumableUploadService.SessionView> chunk(@PathVariable String id, @PathVariable int index,
            @RequestHeader("X-Upload-Chunk-SHA256") String hash,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password, HttpServletRequest request) throws IOException {
        // Avoid @RequestBody byte[]: the service rejects oversized/chunked streams without allocating them.
        return AjaxJson.getSuccessData(uploads.receiveChunk(TlgxyCloudAuthUtil.getCurrentUserId(), id, index, hash,
                request.getInputStream(), decodePassword(password)));
    }

    @PostMapping("/{id}/complete")
    @ApiLimit(timeout = 60, maxCount = 120)
    @DemoDisable
    public AjaxJson<LocalResumableUploadService.SessionView> complete(@PathVariable String id,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        return AjaxJson.getSuccessData(uploads.complete(TlgxyCloudAuthUtil.getCurrentUserId(), id, decodePassword(password)));
    }

    @DeleteMapping("/{id}")
    @ApiLimit(timeout = 60, maxCount = 120)
    @DemoDisable
    public AjaxJson<Void> delete(@PathVariable String id,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
        uploads.delete(TlgxyCloudAuthUtil.getCurrentUserId(), id, decodePassword(password));
        return AjaxJson.getSuccess("仅续传暂存已清理，云盘文件未删除");
    }

    /** HTTP headers are ASCII; the browser sends encodeURIComponent(password), never stores it. */
    static String decodePassword(String encoded) {
        if (encoded == null) return null;
        if (encoded.length() > 9000 || encoded.chars().anyMatch(c -> c < 32 || c > 126))
            throw new BizException("目录密码格式不合法");
        try {
            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            if (decoded.length() > 1000) throw new BizException("目录密码过长");
            return decoded;
        } catch (IllegalArgumentException malformed) { throw new BizException("目录密码格式不合法"); }
    }
}
