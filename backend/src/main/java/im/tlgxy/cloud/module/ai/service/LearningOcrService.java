package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.ai.config.LearningOcrProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bounded local OCR. Fixed executable/arguments; no shell, remote model or user-controlled command. */
@Service
public class LearningOcrService {
    private static final int MAX_SIDE = 2400;
    private static final long MAX_PIXELS = 4_000_000;
    private static final long MAX_SOURCE_IMAGE_PIXELS = 16_000_000;
    private static final Set<String> LANGUAGES = Set.of("eng", "chi_sim", "chi_tra");
    private final LearningOcrProperties settings;
    private final ProcessLauncher launcher;
    private final Semaphore permits;

    @Autowired
    public LearningOcrService(LearningOcrProperties settings) {
        this(settings, ProcessBuilder::start);
    }

    // Package-scoped seam for fake-process tests. Request data never changes the executable/arguments.
    LearningOcrService(LearningOcrProperties settings, ProcessLauncher launcher) {
        this.settings = settings;
        this.launcher = launcher;
        this.permits = new Semaphore(Math.max(1, Math.min(settings.getMaxConcurrent(), 2)), true);
    }

    public boolean isEnabled() { return settings.isEnabled(); }

    public AiAttachmentService.LearningExtraction extractPdf(byte[] bytes) {
        if (!isEnabled()) throw new BizException("本地 OCR 尚未启用，请联系管理员或先转换为带文字层的 PDF");
        if (bytes == null || bytes.length == 0 || bytes.length > AiAttachmentService.MAX_FILE_BYTES)
            throw new BizException("OCR 资料不能为空且不能超过 5 MB");
        Path executable = requireExecutable();
        String language = requireLanguage();
        requireBounds();
        if (!permits.tryAcquire()) throw new BizException("OCR 正在处理其他资料，请稍后重试");
        Path directory = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.getTotalTimeoutSeconds());
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new BizException("暂不支持加密 PDF");
            directory = Files.createTempDirectory("student-cloud-ocr-");
            Path input = directory.resolve("page.png");
            Path output = directory.resolve("page.txt");
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            StringBuilder text = new StringBuilder();
            int covered = 0;
            boolean limited = document.getNumberOfPages() > settings.getMaxPages();
            int pages = Math.min(document.getNumberOfPages(), settings.getMaxPages());
            for (int index = 0; index < pages; index++) {
                if (text.length() >= settings.getMaxChars()) { limited = true; break; }
                checkTime(deadline);
                var page = document.getPage(index);
                float width = page.getCropBox().getWidth();
                float height = page.getCropBox().getHeight();
                if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0
                        || width > 14400 || height > 14400)
                    throw new BizException("PDF 页面尺寸异常，不能安全执行 OCR");
                inspectImages(page.getResources(), Collections.newSetFromMap(new IdentityHashMap<>()), 0, new int[]{0});
                float scale = Math.min(settings.getDpi() / 72f, MAX_SIDE / Math.max(width, height));
                scale = Math.min(scale, (float) Math.sqrt(MAX_PIXELS / ((double) width * height)));
                BufferedImage image = renderer.renderImage(index, scale, ImageType.RGB);
                try {
                    if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE
                            || (long) image.getWidth() * image.getHeight() > MAX_PIXELS)
                        throw new BizException("PDF 渲染图片超过 OCR 安全上限");
                    if (!ImageIO.write(image, "png", input.toFile())) throw new IOException("PNG writer unavailable");
                } finally { image.flush(); }
                if (Files.size(input) > 16L * 1024 * 1024) throw new BizException("OCR 临时图片超过安全上限");
                checkTime(deadline);
                String extracted = runPage(executable, language, input, output, directory, deadline);
                covered++;
                String clean = extracted.trim().replace("\0", "");
                if (!clean.isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    int remaining = settings.getMaxChars() - text.length();
                    if (remaining <= 0) { limited = true; break; }
                    text.append(clean, 0, safeEnd(clean, Math.min(remaining, clean.length())));
                    if (clean.length() > remaining) { limited = true; break; }
                }
                Files.deleteIfExists(input);
                Files.deleteIfExists(output);
            }
            checkTime(deadline);
            String content = text.toString().trim();
            if (content.isEmpty()) throw new BizException("OCR 未识别到可用文字，请检查扫描清晰度或手动转换文字后索引");
            limited |= covered < document.getNumberOfPages();
            String notice = "本地 OCR 已识别前 " + covered + "/" + document.getNumberOfPages()
                    + " 页；图片文字可能识别错误，请人工校对。不保证手写、图表或复杂排版准确。"
                    + (limited ? "达到 OCR 页数或字符上限，剩余内容未索引，请拆分资料。" : "");
            return new AiAttachmentService.LearningExtraction(content, content.length(), covered,
                    document.getNumberOfPages(), limited, notice);
        } catch (BizException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BizException("OCR 已中断，请稍后重试");
        } catch (IOException error) {
            throw new BizException("本地 OCR 无法处理资料，请检查文件和引擎语言包配置");
        } finally {
            cleanup(directory);
            permits.release();
        }
    }

    private String runPage(Path executable, String language, Path input, Path output,
                           Path directory, long deadline) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(List.of(executable.toString(), input.toString(), "stdout",
                "-l", language, "--psm", "3"));
        builder.directory(directory.toFile());
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(output.toFile());
        Process process = launcher.start(builder);
        try {
            long remaining = deadline - System.nanoTime();
            long timeout = Math.min(TimeUnit.SECONDS.toNanos(settings.getPageTimeoutSeconds()), remaining);
            if (timeout <= 0 || !process.waitFor(timeout, TimeUnit.NANOSECONDS))
                throw new BizException("OCR 处理超时，请减少扫描页数或拆分资料");
            if (process.exitValue() != 0) throw new BizException("OCR 引擎执行失败，请检查已安装的语言包");
            long maxOutputBytes = (long) settings.getMaxChars() * 4 + 4096;
            if (Files.size(output) > maxOutputBytes) throw new BizException("OCR 输出超过安全上限，请拆分资料");
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    private Path requireExecutable() {
        try {
            Path path = Path.of(settings.getExecutable());
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!path.isAbsolute() || !Set.of("tesseract", "tesseract.exe").contains(name)
                    || !Files.isRegularFile(path) || !Files.isExecutable(path))
                throw new BizException("本地 OCR 引擎不可用，请由管理员配置 Tesseract 的绝对路径");
            return path.toRealPath();
        } catch (IOException | java.nio.file.InvalidPathException error) {
            throw new BizException("本地 OCR 引擎路径无效，请联系管理员");
        }
    }
    private String requireLanguage() {
        String language = settings.getLanguage();
        if (language == null || language.isBlank() || language.length() > 32
                || !java.util.Arrays.stream(language.split("\\+", -1)).allMatch(LANGUAGES::contains))
            throw new BizException("OCR 语言只能为已支持的 eng、chi_sim、chi_tra 及其组合");
        return language;
    }
    private void requireBounds() {
        if (settings.getMaxPages() < 1 || settings.getMaxPages() > 20 || settings.getDpi() < 72 || settings.getDpi() > 160
                || settings.getMaxChars() < 1000 || settings.getMaxChars() > 120000
                || settings.getPageTimeoutSeconds() < 1 || settings.getPageTimeoutSeconds() > 30
                || settings.getTotalTimeoutSeconds() < 1 || settings.getTotalTimeoutSeconds() > 180
                || settings.getMaxConcurrent() < 1 || settings.getMaxConcurrent() > 2)
            throw new BizException("OCR 限制配置无效，请联系管理员");
    }
    private void checkTime(long deadline) {
        if (System.nanoTime() >= deadline) throw new BizException("OCR 总处理时间达到上限，请拆分资料");
    }
    private void inspectImages(PDResources resources, Set<PDResources> visited, int depth, int[] objects) throws IOException {
        if (resources == null || !visited.add(resources)) return;
        if (depth > 4) throw new BizException("PDF 图片结构过于复杂，不能安全执行 OCR");
        for (var name : resources.getXObjectNames()) {
            if (++objects[0] > 200) throw new BizException("PDF 图片数量过多，请拆分后执行 OCR");
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject image
                    && (long) image.getWidth() * image.getHeight() > MAX_SOURCE_IMAGE_PIXELS)
                throw new BizException("PDF 原始图片像素过大，请降低扫描分辨率");
            if (object instanceof PDFormXObject form) inspectImages(form.getResources(), visited, depth + 1, objects);
        }
    }
    private static int safeEnd(String value, int end) {
        return end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end)) ? end - 1 : end;
    }
    private void cleanup(Path directory) {
        if (directory == null) return;
        // Only the fixed files in our own fresh temporary directory, never user paths or a recursive delete.
        try {
            Files.deleteIfExists(directory.resolve("page.png"));
            Files.deleteIfExists(directory.resolve("page.txt"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) { /* OS cleanup may retry after a forcibly stopped child releases handles. */ }
    }
    @FunctionalInterface interface ProcessLauncher { Process start(ProcessBuilder builder) throws IOException; }
}
