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
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
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

    public static final int MAX_FILES = 3;
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_FILE_CHARS = 12_000;
    static final int MAX_TOTAL_CHARS = 24_000;

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
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new BizException("资料不能为空且不能超过 5 MB");
        }
        try {
            String content = switch (extension(fileName)) {
                case "txt", "md" -> decodeUtf8(bytes, fileName);
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                default -> throw new BizException("资料索引仅支持 TXT、MD、PDF 和 DOCX");
            };
            content = StringUtils.trimToEmpty(content).replace("\0", "");
            if (content.isEmpty()) throw new BizException("未提取到文字；扫描件需要先进行 OCR");
            return truncate(content, MAX_FILE_CHARS);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("资料无法解析，请检查格式或是否加密");
        }
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
