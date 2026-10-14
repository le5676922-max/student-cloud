package im.tlgxy.cloud.module.ai.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LearningExtractionTest {
    private final AiAttachmentService extractor = new AiAttachmentService();

    @Test void indexLongTextIsIndependentOfChatAndIncludesLateContent() {
        String source = "a".repeat(15000) + " latekeyword";
        var result = extractor.extractLearningContent(source.getBytes(StandardCharsets.UTF_8), "note.txt");
        assertEquals(source, result.content());
        assertFalse(result.truncated());
        assertEquals(source.length(), result.coveredChars());
        var chat = extractor.extract(List.of(new MockMultipartFile("files", "note.txt", "text/plain", source.getBytes(StandardCharsets.UTF_8))));
        assertEquals(12000, chat.getFirst().content().length());
        assertFalse(chat.getFirst().content().contains("latekeyword"));
    }

    @Test void characterLimitDoesNotSplitEmojiOrPretendToCoverTheTail() {
        String source = "a".repeat(239999) + "😀" + "secret-tail";
        var result = extractor.extractLearningContent(source.getBytes(StandardCharsets.UTF_8), "note.md");
        assertEquals(239999, result.coveredChars());
        assertTrue(result.truncated());
        assertFalse(Character.isHighSurrogate(result.content().charAt(result.content().length() - 1)));
        assertFalse(result.content().contains("secret-tail"));
    }

    @Test void pdfIndexIncludesPageThirtyOneButChatRemainsThirtyPages() throws Exception {
        byte[] bytes = pdf(32);
        var result = extractor.extractLearningContent(bytes, "note.pdf");
        assertEquals(32, result.totalPages());
        assertEquals(32, result.indexedPages());
        assertFalse(result.truncated());
        assertTrue(result.content().contains("PAGE-31"));
        var chat = extractor.extract(List.of(new MockMultipartFile("files", "note.pdf", "application/pdf", bytes)));
        assertFalse(chat.getFirst().content().contains("PAGE-31"));
    }

    @Test void pdfPageLimitReportsSourcePageCountAndPartialCoverage() throws Exception {
        var result = extractor.extractLearningContent(pdf(301), "note.pdf");
        assertEquals(301, result.totalPages());
        assertEquals(300, result.indexedPages());
        assertTrue(result.truncated());
        assertTrue(result.content().contains("PAGE-300"));
        assertFalse(result.content().contains("PAGE-301"));
    }

    @Test void docxLongParagraphAndLaterTableAreNotCutAtChatBudget() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("a".repeat(14000));
            document.createTable(1, 1).getRow(0).getCell(0).setText("late table keyword");
            document.write(output);
            var result = extractor.extractLearningContent(output.toByteArray(), "note.docx");
            assertTrue(result.content().contains("late table keyword"));
            assertFalse(result.truncated());
        }
    }

    private static byte[] pdf(int pages) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int index = 1; index <= pages; index++) {
                PDPage page = new PDPage(); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(30, 700); stream.showText("PAGE-" + index); stream.endText();
                }
            }
            document.save(output); return output.toByteArray();
        }
    }
}
