package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.model.bo.AiAttachmentContent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAttachmentServiceTest {

    private final AiAttachmentService service = new AiAttachmentService();

    @Test
    void extractsUtf8TextWithoutTrustingClientPath() {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "../notes.md",
                "text/markdown",
                "课程重点：权限校验".getBytes(StandardCharsets.UTF_8)
        );

        List<AiAttachmentContent> result = service.extract(List.of(file));

        assertEquals(1, result.size());
        assertEquals("notes.md", result.getFirst().fileName());
        assertTrue(result.getFirst().content().contains("权限校验"));
    }

    @Test
    void acceptsPlainBrowserFileName() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "course-note.txt", "text/plain", "YUNPAN-ATTACH-723".getBytes(StandardCharsets.UTF_8)
        );

        List<AiAttachmentContent> result = service.extract(List.of(file));

        assertEquals("course-note.txt", result.getFirst().fileName());
        assertTrue(result.getFirst().content().contains("YUNPAN-ATTACH-723"));
    }

    @Test
    void rejectsUnsupportedAndOversizedFiles() {
        MockMultipartFile executable = new MockMultipartFile(
                "files", "run.exe", "application/octet-stream", new byte[]{1}
        );
        MockMultipartFile oversized = new MockMultipartFile(
                "files", "large.txt", "text/plain", new byte[(int) AiAttachmentService.MAX_FILE_BYTES + 1]
        );

        assertThrows(BizException.class, () -> service.extract(List.of(executable)));
        assertThrows(BizException.class, () -> service.extract(List.of(oversized)));
    }

    @Test
    void rejectsMoreThanThreeFiles() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(BizException.class, () -> service.extract(List.of(file, file, file, file)));
    }
}