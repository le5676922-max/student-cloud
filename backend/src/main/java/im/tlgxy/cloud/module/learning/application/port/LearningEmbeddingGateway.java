package im.tlgxy.cloud.module.learning.application.port;

import java.util.List;

/** Optional embedding boundary; callers retain explicit keyword fallback and live file authorization. */
public interface LearningEmbeddingGateway {
    boolean configured();
    int dimensions();
    double minSimilarity();
    String signature();
    List<float[]> embed(List<String> segments);
}
