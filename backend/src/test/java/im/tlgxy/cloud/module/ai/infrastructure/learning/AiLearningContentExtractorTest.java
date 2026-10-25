package im.tlgxy.cloud.module.ai.infrastructure.learning;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiLearningContentExtractorTest {
    private final AiAttachmentService attachments = mock(AiAttachmentService.class);
    private final LearningContentExtractor extractor = new AiLearningContentExtractor(attachments);

    @Test void mapsEveryCoverageFieldWithoutChangingSourceBytesOrInvokingChatExtraction() {
        byte[] bytes = "课程文字".getBytes(StandardCharsets.UTF_8);
        var parsed = new AiAttachmentService.LearningExtraction("课程文字", 4, 2, 8, true, "仅覆盖前两页");
        when(attachments.extractLearningContent(bytes, "notes.pdf")).thenReturn(parsed);

        var extraction = extractor.extract(bytes, "notes.pdf");

        assertEquals(new LearningContentExtractor.Extraction("课程文字", 4, 2, 8, true, "仅覆盖前两页"), extraction);
        verify(attachments).extractLearningContent(same(bytes), eq("notes.pdf"));
        verifyNoMoreInteractions(attachments);
    }

    @Test void preservesNullablePageCoverageForPlainText() {
        byte[] bytes = {1};
        when(attachments.extractLearningContent(bytes, "notes.txt"))
                .thenReturn(new AiAttachmentService.LearningExtraction("text", 4, null, null, false, "文字覆盖"));

        var extraction = extractor.extract(bytes, "notes.txt");

        assertNull(extraction.indexedPages());
        assertNull(extraction.totalPages());
        assertFalse(extraction.truncated());
        assertEquals("文字覆盖", extraction.indexNotice());
        verify(attachments).extractLearningContent(bytes, "notes.txt");
        verifyNoMoreInteractions(attachments);
    }

    @Test void doesNotSwallowParserFailureOrTryAnotherParserOrExternalService() {
        byte[] bytes = {1};
        var failure = new BizException("资料无法解析，请检查格式或是否加密");
        when(attachments.extractLearningContent(bytes, "notes.pdf")).thenThrow(failure);

        assertSame(failure, assertThrows(BizException.class, () -> extractor.extract(bytes, "notes.pdf")));

        verify(attachments).extractLearningContent(bytes, "notes.pdf");
        verifyNoMoreInteractions(attachments);
    }

    @Test void normalTextUsesExistingLocalParserWithNoConfiguredOcrOrModel() {
        LearningContentExtractor local = new AiLearningContentExtractor(new AiAttachmentService());
        byte[] bytes = "普通课程笔记".getBytes(StandardCharsets.UTF_8);

        var extraction = local.extract(bytes, "notes.md");

        assertEquals("普通课程笔记", extraction.content());
        assertEquals(6, extraction.coveredChars());
        assertNull(extraction.totalPages());
        assertFalse(extraction.truncated());
    }
}
