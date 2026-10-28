package io.studentcloud.intelligence.ai;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApiResponse;
import io.studentcloud.common.ApplicationFault;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
@Component
public class LearningKnowledgeClient {
    private final RestClient client;
    public LearningKnowledgeClient(@Qualifier("serviceRestClientBuilder") RestClient.Builder builder){client=builder.build();}
    public record Citation(String reference,String name,int segment,String excerpt,long materialId,long courseId,String storageKey,String path){}
    public record Search(List<Citation> citations,int skipped,boolean truncated){}
    public Search search(Actor actor,Long course,String query){
        try{
            var body=new LinkedHashMap<String,Object>();body.put("accountId",actor.id());body.put("courseId",course);body.put("query",query);body.put("limit",5);
            var result=client.post().uri("http://cloud-content/internal/content/learning/search").body(body)
                .retrieve().body(new ParameterizedTypeReference<ApiResponse<Search>>(){});
            if(result==null || !"0".equals(result.code()) || result.data()==null || result.data().citations()==null)throw unavailable();
            return result.data();
        }catch(org.springframework.web.client.RestClientException failure){throw unavailable();}
    }
    private static ApplicationFault unavailable(){return new ApplicationFault("CONTENT_UNAVAILABLE",503,"暂时无法核验资料权限，请稍后重试");}
}
