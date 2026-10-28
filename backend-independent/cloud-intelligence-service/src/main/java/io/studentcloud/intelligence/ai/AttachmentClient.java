package io.studentcloud.intelligence.ai;
import io.studentcloud.common.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
@Component
public class AttachmentClient {
    private final RestClient client;
    public AttachmentClient(@Qualifier("serviceRestClientBuilder") RestClient.Builder builder){client=builder.build();}
    public String extract(List<MultipartFile> files){
        if(files==null || files.isEmpty() || files.size()>3 || files.stream().mapToLong(MultipartFile::getSize).sum()>4*1024*1024)throw invalid();
        var parts=new LinkedMultiValueMap<String,Object>();
        try{
            for(var file:files){
                String name=file.getOriginalFilename();if(name==null || name.length()>255 || name.chars().anyMatch(Character::isISOControl) || file.getSize()>2*1024*1024)throw invalid();
                parts.add("files",new ByteArrayResource(file.getBytes()){@Override public String getFilename(){return name;}});
            }
            var result=client.post().uri("http://cloud-content/internal/content/attachments/extract").contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<Map<String,Object>>>(){});
            if(result==null || result.data()==null || !(result.data().get("text") instanceof String text) || text.length()>6000)throw invalid();
            return text;
        }catch(IOException failure){throw invalid();}
        catch(org.springframework.web.client.RestClientException failure){throw new ApplicationFault("ATTACHMENT_UNAVAILABLE",503,"附件解析未完成，请使用TXT或MD缩小后重试");}
    }
    private static ApplicationFault invalid(){return new ApplicationFault("41000",400,"最多3个TXT、MD、PDF、DOCX附件；单个2MiB，合计4MiB");}
}
