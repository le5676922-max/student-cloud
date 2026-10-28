package io.studentcloud.content.http;
import io.studentcloud.common.*;
import io.studentcloud.content.learning.LearningTextExtractor;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
public class InternalAttachmentController {
    private final Semaphore parsing=new Semaphore(2);
    @PostMapping("/internal/content/attachments/extract")
    public ApiResponse<Map<String,Object>> extract(@RequestPart("files") List<MultipartFile> files){
        if(files==null || files.isEmpty() || files.size()>3 || files.stream().mapToLong(MultipartFile::getSize).sum()>4*1024*1024)throw invalid();
        if(!parsing.tryAcquire())throw new ApplicationFault("PARSE_BUSY",429,"附件解析繁忙，请稍后重试");
        try{
            StringBuilder context=new StringBuilder();boolean truncated=false;
            for(var file:files){
                String name=file.getOriginalFilename();
                if(name==null || name.length()>255 || name.chars().anyMatch(Character::isISOControl) || file.getSize()>2*1024*1024)throw invalid();
                var text=LearningTextExtractor.extract(name,file.getBytes());
                int remaining=6000-context.length();if(remaining<100){truncated=true;break;}
                context.append("\n附件 ").append(name.replace('\\','_').replace('/','_')).append("：\n");
                remaining=6000-context.length();
                context.append(text.text(),0,Math.min(remaining,text.text().length()));
                truncated|=text.truncated() || text.text().length()>remaining;
            }
            return ApiResponse.success(Map.of("text",context.toString(),"truncated",truncated));
        }catch(IOException | IllegalArgumentException failure){throw new ApplicationFault("41000",400,"附件格式不支持或无法解析；请使用TXT、MD、PDF或DOCX");}
        finally{parsing.release();}
    }
    private static ApplicationFault invalid(){return new ApplicationFault("41000",400,"每轮最多3个附件，单个2MiB，合计4MiB");}
}
