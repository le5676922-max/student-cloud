package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.service.AiAttachmentService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class LearningLibraryTest {
    @TempDir Path directory;
    private final AiAttachmentService extractor = new AiAttachmentService();

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "/a/../secret.txt", "C:\\secret.txt", "/a/./b.txt", "/%2e%2e/secret.txt", "/", "/a\u0000.txt"})
    void rejectsUnsafePaths(String path) {
        assertThrows(BizException.class, () -> LearningFileReader.normalizePath(path));
    }

    @Test void normalizesUserRelativePaths() {
        assertEquals("/课程/笔记.txt", LearningFileReader.normalizePath("课程\\笔记.txt"));
        assertEquals("/notes/a.txt", LearningFileReader.normalizePath("//notes///a.txt"));
    }

    @Test void cannotEscapeCanonicalStorageRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root")).toRealPath();
        Path outside = Files.writeString(directory.resolve("secret.txt"), "secret");
        assertThrows(BizException.class, () -> LearningFileReader.confined(root, "../secret.txt"));
        assertThrows(BizException.class, () -> LearningFileReader.confined(root, outside.toString()));
        Path note = Files.writeString(root.resolve("note.txt"), "study");
        assertEquals(note.toRealPath(), LearningFileReader.confined(root, "note.txt"));
    }

    @Test void chineseBigramsAndEnglishTokensAreSearchable() {
        assertTrue(LearningLibraryService.terms("数据库 SQL transaction").containsAll(java.util.Set.of("数据", "据库", "sql", "transaction")));
        assertTrue(LearningLibraryService.terms("？！？").isEmpty());
    }

    @Test void contentHashDetectsSameSizeOverwrite() {
        assertNotEquals(LearningLibraryService.fingerprint("hello cloud".getBytes(StandardCharsets.UTF_8)),
                LearningLibraryService.fingerprint("hello world".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void boundedUtf8ExtractionRejectsInvalidInput() {
        String text = extractor.extractLearningText("资料内容".getBytes(StandardCharsets.UTF_8), "note.MD");
        assertEquals("资料内容", text);
        String truncated = extractor.extractLearningText("a".repeat(13000).getBytes(StandardCharsets.UTF_8), "note.txt");
        assertEquals(12000, truncated.length());
        assertTrue(truncated.contains("已截断"));
        assertThrows(BizException.class, () -> extractor.extractLearningText(new byte[]{(byte) 0xff}, "note.txt"));
        assertThrows(BizException.class, () -> extractor.extractLearningText(new byte[0], "note.txt"));
        assertThrows(BizException.class, () -> extractor.extractLearningText(new byte[LearningFileReader.MAX_BYTES + 1], "note.txt"));
        assertThrows(BizException.class, () -> extractor.extractLearningText(new byte[]{1}, "note.exe"));
    }

    @Test void extractsDocxParagraphsAndTables() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("数据库事务");
            document.createTable(1, 1).getRow(0).getCell(0).setText("原子性");
            document.write(output);
            String text = extractor.extractLearningText(output.toByteArray(), "note.docx");
            assertTrue(text.contains("数据库事务"));
            assertTrue(text.contains("原子性"));
        }
    }

    @Test void extractsTextPdfAndRejectsEmptyScannedPdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            document.save(output);
            assertThrows(BizException.class, () -> extractor.extractLearningText(output.toByteArray(), "scan.pdf"));
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(30, 700);
                stream.showText("SQL transaction");
                stream.endText();
            }
            output.reset();
            document.save(output);
            assertTrue(extractor.extractLearningText(output.toByteArray(), "note.pdf").contains("SQL transaction"));
        }
    }
}
