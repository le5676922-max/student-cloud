package im.tlgxy.cloud.core.util;

import cn.hutool.core.io.FileUtil;
import im.tlgxy.cloud.core.exception.ErrorCode;
import im.tlgxy.cloud.core.exception.status.NotFoundAccessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 文件响应工具类
 * <p>
 * 用于将文件对象封装为 Spring MVC 的 {@link ResponseEntity}，以支持文件下载功能。
 * 当前仅支持单线程下载，不支持断点续传。返回的响应体使用流式传输
 * ({@link InputStreamResource})，避免将整个文件加载到内存中。
 * </p>
 *
 * @author zhaojun
 */
@Slf4j
public class FileResponseUtil {


    /**
     * 将文件封装为下载响应实体（单线程下载，不支持断点续传）
     * <p>
     * 如果文件不存在，会抛出 {@link NotFoundAccessException} 异常。
     * 响应的 Content-Type 为 application/octet-stream（二进制流），
     * 文件名使用 UTF-8 编码以防止中文乱码。
     * </p>
     *
     * @param   file
     *          要下载的文件对象
     *
     * @param   fileName
     *          下载时保存为的文件名，如果为空则使用文件的原始名称
     *
     * @return  包含文件流资源的 ResponseEntity 对象
     *
     * @throws NotFoundAccessException 如果文件不存在
     */
    public static ResponseEntity<Resource> exportSingleThread(File file, String fileName) {
        // 检查文件是否存在，不存在则抛出业务异常
        if (!file.exists()) {
            throw new NotFoundAccessException(ErrorCode.BIZ_FILE_NOT_EXIST);
        }

        // 设置响应内容类型为二进制流
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;

        HttpHeaders headers = new HttpHeaders();

        // 如果文件名为空，使用文件原始名称
        if (StringUtils.isEmpty(fileName)) {
            fileName = file.getName();
        }

        // 构建 Content-Disposition 头，inline 表示浏览器内联显示，使用 UTF-8 编码文件名防止中文乱码
        ContentDisposition contentDisposition = ContentDisposition
                .builder("inline")
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        headers.setContentDisposition(contentDisposition);

        // 构建 ResponseEntity，设置响应头、内容长度、内容类型和流式响应体
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.length())
                .contentType(mediaType)
                .body(new InputStreamResource(FileUtil.getInputStream(file)));
    }

}