package im.tlgxy.cloud.module.learning.application.port;

/**
 * Learning-owned contract for bounded document parsing. Implementations must not persist
 * the source bytes or call an external model merely to extract readable text.
 */
public interface LearningContentExtractor {
    Extraction extract(byte[] bytes, String fileName);

    /** Coverage describes extracted text only, not the complete meaning of the source document. */
    record Extraction(String content, int coveredChars, Integer indexedPages,
                      Integer totalPages, boolean truncated, String indexNotice) { }
}
