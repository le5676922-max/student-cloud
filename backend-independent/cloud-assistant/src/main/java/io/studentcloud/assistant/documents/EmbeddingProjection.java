package io.studentcloud.assistant.documents;

import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Explicitly opted-in projection contract, NOT a chat-completions adapter.
 * No HTTP implementation or paid provider is installed by default. Adapters own fixed,
 * administrator-approved URLs, timeouts and request budgets; user URLs are never accepted.
 */
public final class EmbeddingProjection {
    public interface Provider { Batch embed(List<String> texts); }
    public record Vector(int inputIndex,List<Double> values) { }
    public record Batch(String modelId,String modelVersion,int dimensions,List<Vector> vectors) { }
    public record Result(String mode,String reason,List<List<Double>> vectors) { }
    private final boolean enabled;
    private final String model,version;
    private final int dimensions;
    private final Provider provider;
    public EmbeddingProjection(boolean enabled,String model,String version,int dimensions,Provider provider) {
        if(model==null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") || version==null
                || !version.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") || dimensions<1 || dimensions>8192)
            throw new ApplicationFault("EMBEDDING_CONFIG_INVALID",400,"嵌入模型配置无效");
        this.enabled=enabled;this.model=model;this.version=version;this.dimensions=dimensions;this.provider=provider;
    }
    public Result embed(List<String> texts,boolean consent) {
        if(texts==null || texts.isEmpty() || texts.size()>16) throw input();
        int bytes=0;
        for(String text:texts) {
            if(text==null || text.isBlank() || text.length()>4000) throw input();
            bytes+=text.getBytes(StandardCharsets.UTF_8).length;
        }
        if(bytes>32*1024) throw input();
        if(!enabled) return lexical("EXTERNAL_DISABLED");
        if(!consent) return lexical("CONSENT_REQUIRED");
        if(provider==null) return lexical("PROVIDER_UNCONFIGURED");
        Batch batch;
        try {batch=provider.embed(List.copyOf(texts));}
        catch(ApplicationFault safe) {throw safe;}
        catch(RuntimeException unavailable) {throw new ApplicationFault("EMBEDDING_PROVIDER_UNAVAILABLE",503,"嵌入服务暂不可用，词法索引不受影响");}
        if(batch==null || !model.equals(batch.modelId()) || !version.equals(batch.modelVersion())
                || dimensions!=batch.dimensions() || batch.vectors()==null || batch.vectors().size()!=texts.size()) throw protocol();
        var ordered=new ArrayList<List<Double>>(java.util.Collections.nCopies(texts.size(),null));
        for(Vector vector:batch.vectors()) {
            if(vector==null || vector.inputIndex()<0 || vector.inputIndex()>=texts.size() || ordered.get(vector.inputIndex())!=null
                    || vector.values()==null || vector.values().size()!=dimensions
                    || vector.values().stream().anyMatch(v->v==null || !Double.isFinite(v))) throw protocol();
            ordered.set(vector.inputIndex(),List.copyOf(vector.values()));
        }
        if(ordered.contains(null)) throw protocol();
        // VECTOR_READY is not a claim that retrieval actually used these vectors.
        return new Result("VECTOR_READY","VALIDATED",List.copyOf(ordered));
    }
    private static Result lexical(String reason) {return new Result("LEXICAL",reason,List.of());}
    private static ApplicationFault input() {return new ApplicationFault("EMBEDDING_INPUT_INVALID",400,"嵌入片段超过批次或文本预算");}
    private static ApplicationFault protocol() {return new ApplicationFault("EMBEDDING_PROTOCOL_INVALID",502,"嵌入响应模型、维度或序号不匹配，未接受向量");}
}
