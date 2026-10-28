package io.studentcloud.content.http;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.DrivePath;
import io.studentcloud.drive.FileRecord;
import io.studentcloud.drive.application.DriveOperations;
import io.studentcloud.runtime.http.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** Private preview bytes are authenticated and rendered locally, never by an external document viewer. */
@RestController
public final class PreviewController {
    static final long MAX_BINARY_BYTES=PreviewContentPolicy.MAX_BINARY_BYTES;
    static final long MAX_TEXT_BYTES=PreviewContentPolicy.MAX_TEXT_BYTES;
    private final DriveOperations mounts;
    // Includes validation AND slow response output. Never release a buffer's slot before it is written.
    private final Semaphore slots=new Semaphore(2);

    public PreviewController(DriveOperations mounts){this.mounts=mounts;}
    static PreviewContentPolicy.Format format(FileRecord file){return PreviewContentPolicy.format(file);}

    @GetMapping("/api/drives/{key}/preview")
    public void preview(@PathVariable String key,@RequestParam String path,HttpServletRequest request,HttpServletResponse response)throws IOException{
        DrivePath virtual;
        try{virtual=DrivePath.of(path);}catch(IllegalArgumentException invalid){throw fault("PREVIEW_PATH_INVALID",400,"文件路径无效");}
        if(!slots.tryAcquire())throw fault("PREVIEW_BUSY",429,"正在处理其他预览，请稍后再试");
        try{
            FileRecord original;
            PreviewContentPolicy.Format type;
            byte[] bytes;
            try(var file=mounts.readWithMetadata(AuthenticationFilter.actor(request),key,virtual)){
                original=file.metadata();type=format(original);
                if(type==null)throw fault("PREVIEW_UNSUPPORTED",415,"此格式暂不支持本地预览，请下载后查看");
                long size=original.size();
                if(size<0||size>type.limit())throw fault("PREVIEW_TOO_LARGE",413,"文件超过此格式的预览预算，请下载后查看");
                bytes=file.input().readNBytes(Math.toIntExact(size)+1);
                if(bytes.length!=size)throw changed();
            }
            PreviewContentPolicy.validate(type,bytes);
            // Decoding or a remote read can outlive a permission/root change. Reauthorize before output.
            // Do not hold the first remote stream while obtaining a second connection.
            try(var checked=mounts.readWithMetadata(AuthenticationFilter.actor(request),key,virtual)){
                if(!original.equals(checked.metadata()))throw changed();
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Security-Policy","default-src 'none'; base-uri 'none'; frame-ancestors 'self'; sandbox");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(original.name(),StandardCharsets.UTF_8).build().toString());
            response.setContentType(type.mediaType());response.setContentLengthLong(bytes.length);
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        }catch(IOException failure){if(response.isCommitted())throw failure;throw fault("PREVIEW_READ_FAILED",409,"文件读取失败，请刷新后重试");}
        finally{slots.release();}
    }

    private static ApplicationFault changed(){return fault("PREVIEW_CHANGED",409,"文件或访问范围已变化，请刷新后重新预览");}
    private static ApplicationFault fault(String code,int status,String message){return new ApplicationFault(code,status,message);}
}
