package im.tlgxy.cloud.module.ai.infrastructure.learning;

import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import im.tlgxy.cloud.module.learning.application.port.LearningContentExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bridges the existing bounded text/OCR parser without leaking AI implementation DTOs into learning. */
@Component
@RequiredArgsConstructor
public class AiLearningContentExtractor implements LearningContentExtractor {
    private final AiAttachmentService attachments;

    @Override
    public Extraction extract(byte[] bytes, String fileName) {
        var extraction = attachments.extractLearningContent(bytes, fileName);
        return new Extraction(extraction.content(), extraction.coveredChars(), extraction.indexedPages(),
                extraction.totalPages(), extraction.truncated(), extraction.indexNotice());
    }
}
