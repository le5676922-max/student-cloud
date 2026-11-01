package io.studentcloud.assistant.documents;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Rank-only RRF: never add incompatible lexical and vector similarity scores. */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() { }
    public record Hit(String id,double score) { }
    public static List<Hit> fuse(List<String> lexical,List<String> vector,int limit) {
        if(lexical==null || vector==null || lexical.size()>200 || vector.size()>200 || limit<1 || limit>8)
            throw new IllegalArgumentException("Invalid bounded retrieval ranks");
        var scores=new LinkedHashMap<String,Double>();
        add(scores,lexical);add(scores,vector);
        return scores.entrySet().stream().map(entry->new Hit(entry.getKey(),entry.getValue()))
                .sorted(java.util.Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id))
                .limit(limit).toList();
    }
    private static void add(Map<String,Double> scores,List<String> ids) {
        int rank=0;
        for(String id:new LinkedHashSet<>(ids)) {
            if(id==null || id.isBlank()) throw new IllegalArgumentException("Missing retrieval identity");
            if(++rank>20) break;
            scores.merge(id,1d/(60+rank),Double::sum);
        }
    }
}
