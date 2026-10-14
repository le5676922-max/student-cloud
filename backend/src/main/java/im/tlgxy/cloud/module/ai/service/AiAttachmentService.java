package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.model.bo.AiAttachmentContent;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 安全地读取本轮 AI 对话附件，不落盘、不缓存。 */
@Service
public class AiAttachmentService {

    @Autowired(required = false)
    private LearningOcrService learningOcr;

    public static final int MAX_FILES = 3;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_FILE_CHARS = 12_000;
    static final int MAX_TOTAL_CHARS = 24_000;
    public static final int MAX_LEARNING_CHARS = 240_000;
    public static final int MAX_LEARNING_PDF_PAGES = 300;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "properties", "log",
            "java", "kt", "js", "jsx", "ts", "tsx", "vue", "css", "scss", "less", "html", "htm",
            "sql", "py", "go", "rs", "c", "h", "cpp", "hpp", "sh", "ps1"
    );

    public List<AiAttachmentContent> extract(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > MAX_FILES) {
            throw new BizException("每次最多上传 " + MAX_FILES + " 个附件");
        }

        List<AiAttachmentContent> attachments = new ArrayList<>();
        int remainingChars = MAX_TOTAL_CHARS;
        for (MultipartFile file : files) {
            String fileName = safeFileName(file.getOriginalFilename());
            validateFile(file, fileName);
            String content = extractContent(file, fileName);
            content = StringUtils.trimToEmpty(content).replace("\0", "");
            if (content.isEmpty()) {
                throw new BizException("附件内容为空或无法提取文字：" + fileName);
            }

            int limit = Math.min(MAX_FILE_CHARS, remainingChars);
            if (limit <= 0) {
                throw new BizException("附件文字总量过大，请减少文件数量或内容");
            }
            content = truncate(content, limit);
            remainingChars -= content.length();
            attachments.add(new AiAttachmentContent(fileName, content));
        }
        return List.copyOf(attachments);
    }

    private String truncate(String content, int limit) {
        if (content.length() <= limit) {
            return content;
        }

        String marker = "\n[附件内容已截断]";
        if (limit <= marker.length()) {
            return marker.substring(0, limit);
        }
        return content.substring(0, limit - marker.length()) + marker;
    }

    private void validateFile(MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            throw new BizException("不能上传空附件：" + fileName);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException("附件不能超过 5 MB：" + fileName);
        }
        String extension = extension(fileName);
        if (!TEXT_EXTENSIONS.contains(extension)
                && !Set.of("pdf", "docx", "xls", "xlsx").contains(extension)) {
            throw new BizException("暂不支持该附件格式：" + fileName);
        }
    }

    private String extractContent(MultipartFile file, String fileName) {
        String extension = extension(fileName);
        try {
            byte[] bytes = file.getBytes();
            if (TEXT_EXTENSIONS.contains(extension)) {
                return decodeUtf8(bytes, fileName);
            }
            return switch (extension) {
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "xls", "xlsx" -> extractWorkbook(bytes);
                default -> throw new BizException("暂不支持该附件格式：" + fileName);
            };
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("无法读取附件，请确认文件未损坏或加密：" + fileName, e);
        }
    }

    /** Bounded, explicit persistent indexing format set. The original bytes are never stored. */
    public String extractLearningText(byte[] bytes, String fileName) {
        return extractLearningContent(bytes, fileName).content();
    }

    /** Persistent retrieval has its own limits; chat attachment limits remain unchanged. */
    public LearningExtraction extractLearningContent(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new BizException("资料不能为空且不能超过 5 MB");
        }
        try {
            LearningExtraction extraction = switch (extension(fileName)) {
                case "txt", "md" -> learningText(decodeUtf8(bytes, fileName));
                case "pdf" -> extractLearningPdf(bytes);
                case "docx" -> extractLearningDocx(bytes);
                default -> throw new BizException("资料索引仅支持 TXT、MD、PDF 和 DOCX");
            };
            if (extraction.content().isEmpty()) throw new BizException("未提取到文字；扫描 PDF 需要管理员启用本地 OCR 或先转换为带文字层的文件");
            return extraction;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("资料无法解析，请检查格式或是否加密");
        }
    }

    public record LearningExtraction(String content, int coveredChars, Integer indexedPages,
                                     Integer totalPages, boolean truncated, String indexNotice) { }

    private LearningExtraction learningText(String raw) {
        String clean = StringUtils.trimToEmpty(raw).replace("\0", "");
        boolean limited = clean.length() > MAX_LEARNING_CHARS;
        String content = clean.substring(0, safeLimit(clean, MAX_LEARNING_CHARS));
        return learningExtraction(content, null, null, limited);
    }

    private LearningExtraction learningExtraction(String raw, Integer pages, Integer totalPages, boolean limited) {
        String content = StringUtils.trimToEmpty(raw).replace("\0", "");
        String notice = limited
                ? "仅索引已提取范围；达到 240,000 字符或 PDF 300 页上限，末页可能仅部分覆盖。请拆分文件后索引剩余内容。"
                : "已索引可提取文字；不包含扫描图片、图表图片或未能解析的内容。";
        return new LearningExtraction(content, content.length(), pages, totalPages, limited, notice);
    }

    private LearningExtraction extractLearningPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new BizException("暂不支持加密 PDF");
            BoundedTextWriter output = new BoundedTextWriter(MAX_LEARNING_CHARS);
            int[] visited = {0};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    visited[0]++;
                    super.startPage(page);
                }
            };
            stripper.setEndPage(Math.min(document.getNumberOfPages(), MAX_LEARNING_PDF_PAGES));
            try { stripper.writeText(document, output); }
            catch (TextLimitReached ignored) { /* Stop parsing later pages when the text budget is exhausted. */ }
            if (output.content().isBlank() && learningOcr != null && learningOcr.isEnabled()) {
                return learningOcr.extractPdf(bytes);
            }
            return learningExtraction(output.content(), visited[0], document.getNumberOfPages(),
                    output.limited || document.getNumberOfPages() > MAX_LEARNING_PDF_PAGES);
        }
    }

    private LearningExtraction extractLearningDocx(byte[] bytes) throws Exception {
        validateLearningArchive(bytes);
        BoundedTextWriter output = new BoundedTextWriter(MAX_LEARNING_CHARS);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            try {
                for (var element : document.getBodyElements()) {
                    if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph)
                        output.write(paragraph.getText() + "\n");
                    else if (element instanceof XWPFTable table) {
                        for (XWPFTableRow row : table.getRows())
                            for (XWPFTableCell cell : row.getTableCells()) output.write(cell.getText() + "\n");
                    }
                }
            } catch (TextLimitReached ignored) { /* The persistent index is bounded independently of chat. */ }
        }
        return learningExtraction(output.content(), null, null, output.limited);
    }

    /** A small compressed DOCX must not expand without bound before the text limit is applied. */
    private void validateLearningArchive(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                if (++entries > 2000) throw new BizException("DOCX 文件结构过于复杂，请拆分后重新索引");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 24L * 1024 * 1024)
                        throw new BizException("DOCX 解压内容超过 24 MB 安全上限，请拆分后重新索引");
                }
                zip.closeEntry();
            }
        }
    }

    private static int safeLimit(String value, int limit) {
        int end = Math.min(value.length(), limit);
        return end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end)) ? end - 1 : end;
    }

    private static final class TextLimitReached extends IOException { }
    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean limited;
        private BoundedTextWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            int remaining = limit - text.length();
            text.append(chars, offset, Math.min(remaining, length));
            if (length > remaining) { limited = true; throw new TextLimitReached(); }
        }
        private String content() {
            String result = text.toString();
            if (limited && !result.isEmpty() && Character.isHighSurrogate(result.charAt(result.length() - 1)))
                return result.substring(0, result.length() - 1);
            return result;
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private String decodeUtf8(byte[] bytes, String fileName) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new BizException("文本附件必须使用 UTF-8 编码：" + fileName);
        }
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new BizException("暂不支持加密 PDF");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(document.getNumberOfPages(), 30));
            return stripper.getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            document.getParagraphs().forEach(paragraph -> appendLine(result, paragraph.getText()));
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        appendLine(result, cell.getText());
                    }
                }
            }
        }
        return result.toString();
    }

    private String extractWorkbook(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int sheetCount = Math.min(workbook.getNumberOfSheets(), 10);
            for (int sheetIndex = 0; sheetIndex < sheetCount && result.length() < MAX_FILE_CHARS; sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                appendLine(result, "[工作表：" + sheet.getSheetName() + "]");
                int rowCount = 0;
                for (Row row : sheet) {
                    if (rowCount++ >= 500 || result.length() >= MAX_FILE_CHARS) {
                        break;
                    }
                    List<String> values = new ArrayList<>();
                    int cellCount = 0;
                    for (Cell cell : row) {
                        if (cellCount++ >= 50) {
                            break;
                        }
                        values.add(formatter.formatCellValue(cell));
                    }
                    appendLine(result, String.join("\t", values));
                }
            }
        }
        return result.toString();
    }

    private void appendLine(StringBuilder target, String value) {
        if (target.length() >= MAX_FILE_CHARS || StringUtils.isBlank(value)) {
            return;
        }
        int remaining = MAX_FILE_CHARS - target.length();
        String line = value.length() > remaining ? value.substring(0, remaining) : value;
        target.append(line).append('\n');
    }

    private String safeFileName(String originalName) {
        String name = StringUtils.defaultIfBlank(originalName, "未命名文件").replace((char) 92, '/');
        int slashIndex = name.lastIndexOf('/');
        if (slashIndex >= 0) {
            name = name.substring(slashIndex + 1);
        }
        name = name.replaceAll("[\\r\\n]", "_");
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(name, "未命名文件"), 120);
    }

    private String extension(String fileName) {
        return StringUtils.substringAfterLast(fileName, ".").toLowerCase(Locale.ROOT);
    }
}
