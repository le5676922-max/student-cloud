package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.config.LearningOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fake engine/process only; these tests do not claim a real Tesseract accuracy benchmark. */
class LearningOcrServiceTest {
    @TempDir Path work;

    @Test void defaultAttachmentServiceDoesNotPretendScansWereIndexed() throws Exception {
        var exception = assertThrows(BizException.class,
                () -> new AiAttachmentService().extractLearningContent(pdf(1, false), "scan.pdf"));
        assertTrue(exception.getMessage().contains("OCR"));
    }

    @Test void pdfWithoutTextUsesOptionalOcrButTextPdfDoesNot() throws Exception {
        LearningOcrService engine = mock(LearningOcrService.class);
        when(engine.isEnabled()).thenReturn(true);
        when(engine.extractPdf(any())).thenReturn(new AiAttachmentService.LearningExtraction(
                "fake scanned text", 17, 1, 1, false, "本地 OCR"));
        var attachments = new AiAttachmentService();
        ReflectionTestUtils.setField(attachments, "learningOcr", engine);
        assertEquals("fake scanned text", attachments.extractLearningContent(pdf(1, false), "scan.pdf").content());
        assertTrue(attachments.extractLearningContent(pdf(1, true), "normal.pdf").content().contains("Normal text"));
        verify(engine, times(1)).extractPdf(any());
    }

    @Test void fixedArgumentsAndImageOutputsArePrivateAndCleaned() throws Exception {
        var settings = enabledSettings();
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Path> temp = new AtomicReference<>();
        var engine = new LearningOcrService(settings, builder -> {
            command.set(List.copyOf(builder.command()));
            temp.set(builder.directory().toPath());
            assertTrue(Files.size(Path.of(builder.command().get(1))) > 0);
            assertEquals("1", builder.environment().get("OMP_THREAD_LIMIT"));
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
            Files.writeString(builder.redirectOutput().file().toPath(), "模拟中文识别\nLearning notes");
            return completedProcess();
        });
        var result = engine.extractPdf(pdf(1, false));
        assertTrue(result.content().contains("模拟中文"));
        assertTrue(result.indexNotice().contains("OCR"));
        assertEquals(List.of(settings.getExecutable(), command.get().get(1), "stdout", "-l", "chi_sim+eng", "--psm", "3"), command.get());
        assertFalse(Files.exists(temp.get()), "Temporary page and text must be removed");
    }

    @Test void languageAndExecutableCannotBecomeShellCommands() throws Exception {
        var settings = enabledSettings();
        settings.setLanguage("eng; touch /tmp/not-allowed");
        var engine = new LearningOcrService(settings, builder -> fail("Invalid config must not spawn anything"));
        assertThrows(BizException.class, () -> engine.extractPdf(pdf(1, false)));
        settings.setLanguage("eng");
        settings.setExecutable("sh -c tesseract");
        assertThrows(BizException.class, () -> engine.extractPdf(pdf(1, false)));
    }

    @Test void excessPagesAreExplicitlyReportedAndCharactersBounded() throws Exception {
        var settings = enabledSettings();
        settings.setMaxPages(1);
        settings.setMaxChars(1000);
        var engine = new LearningOcrService(settings, builder -> {
            Files.writeString(builder.redirectOutput().file().toPath(), "识别结果".repeat(500));
            return completedProcess();
        });
        var result = engine.extractPdf(pdf(2, false));
        assertEquals(1000, result.coveredChars());
        assertTrue(result.truncated());
        assertEquals(1, result.indexedPages());
        assertEquals(2, result.totalPages());
        assertTrue(result.indexNotice().contains("剩余内容未索引"));
    }

    @Test void timedOutEngineIsDestroyedAndTemporaryFilesAreRemoved() throws Exception {
        var settings = enabledSettings();
        AtomicReference<Path> temp = new AtomicReference<>();
        Process process = mock(Process.class);
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenReturn(java.util.stream.Stream.empty());
        var engine = new LearningOcrService(settings, builder -> { temp.set(builder.directory().toPath()); return process; });
        var error = assertThrows(BizException.class, () -> engine.extractPdf(pdf(1, false)));
        assertTrue(error.getMessage().contains("超时"));
        verify(process).destroyForcibly();
        assertFalse(Files.exists(temp.get()));
    }

    @Test void emptyOrFailedRecognitionDoesNotCreateAUsableIndex() throws Exception {
        var settings = enabledSettings();
        var empty = new LearningOcrService(settings, builder -> {
            Files.writeString(builder.redirectOutput().file().toPath(), " \n");
            return completedProcess();
        });
        assertThrows(BizException.class, () -> empty.extractPdf(pdf(1, false)));
        var failure = new LearningOcrService(settings, builder -> {
            var process = completedProcess(); when(process.exitValue()).thenReturn(1); return process;
        });
        assertThrows(BizException.class, () -> failure.extractPdf(pdf(1, false)));
    }

    @Test void fullCharacterBudgetNeverOverflowsWithPageSeparator() throws Exception {
        var settings = enabledSettings();
        settings.setMaxChars(1000);
        var engine = new LearningOcrService(settings, builder -> {
            Files.writeString(builder.redirectOutput().file().toPath(), "a".repeat(1000));
            return completedProcess();
        });
        var result = engine.extractPdf(pdf(2, false));
        assertEquals(1000, result.content().length());
        assertEquals(1, result.indexedPages());
        assertTrue(result.truncated());
    }

    private LearningOcrProperties enabledSettings() throws Exception {
        var settings = new LearningOcrProperties();
        settings.setEnabled(true);
        Path executable = Files.createFile(work.resolve("tesseract"));
        executable.toFile().setExecutable(true);
        settings.setExecutable(executable.toRealPath().toString());
        return settings;
    }
    private Process completedProcess() {
        Process process = mock(Process.class);
        try { when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true); }
        catch (InterruptedException impossible) { throw new AssertionError(impossible); }
        when(process.exitValue()).thenReturn(0);
        when(process.isAlive()).thenReturn(false);
        return process;
    }
    private byte[] pdf(int pages, boolean text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text) try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700);
                    stream.showText("Normal text");
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
