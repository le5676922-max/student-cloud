package im.tlgxy.cloud.module.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.service.LearningEmbeddingClient;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import im.tlgxy.cloud.module.learning.model.MaterialCitation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** Optional bounded vectors; never a shared permission/content cache or a replacement for live file validation. */
@Component
@RequiredArgsConstructor
public class LearningSemanticIndex {
    static final int MAX_SEGMENTS = 64;
    // Float JSON is ASCII. Keep this aligned with the SQL byte-length gate before JDBC.
    static final int MAX_JSON_CHARS = 1024 * 1024;
    private final LearningEmbeddingClient client;
    private final ObjectMapper json;

    public boolean configured() { return client.configured(); }

    /** Called only after the file was parsed and its fresh authority/hash checked, outside a DB transaction. */
    public void prepare(LearningMaterial material) {
        // Refresh must clear old vectors even if the provider is disabled, broken or changed.
        material.setEmbeddingJson(null);
        material.setEmbeddingSignature(null);
        material.setSemanticSegments(0);
        material.setSemanticNotice("语义检索未启用；当前使用关键词检索。");
        if (!configured()) return;
        List<String> excerpts = new ArrayList<>();
        String content = material.getContent();
        // Budget 16 characters per float, plus commas and array delimiters, before sending text.
        // High-dimensional models therefore index fewer segments, not an oversized JSON blob.
        int segmentBudget = Math.min(MAX_SEGMENTS, (MAX_JSON_CHARS - 2) / (client.dimensions() * 17 + 3));
        for (int start = 0; start < content.length() && excerpts.size() < segmentBudget; start += 600)
            excerpts.add(content.substring(start, Math.min(start + 700, content.length())));
        if (excerpts.isEmpty()) return;
        try {
            List<float[]> vectors = client.embed(excerpts);
            if (vectors.size() != excerpts.size()) throw new IllegalArgumentException("Invalid vector count");
            for (float[] vector : vectors) validate(vector);
            String encoded = json.writeValueAsString(vectors);
            if (encoded.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Vector index too large");
            material.setEmbeddingJson(encoded);
            material.setEmbeddingSignature(client.signature());
            material.setSemanticSegments(vectors.size());
            boolean partial = content.length() > (vectors.size() - 1) * 600 + 700;
            material.setSemanticNotice("语义索引覆盖前 " + vectors.size() + " 个文字片段"
                    + (partial ? "，后续文字仍可使用关键词检索。" : "；不代表未提取的图片或附件内容。"));
        } catch (Exception unavailable) {
            // No raw provider error, response, URL or credential may reach the material/API/log.
            material.setSemanticNotice("嵌入服务暂不可用，本次仅建立文字索引；可稍后重新索引。");
        }
    }

    public record Ranking(List<MaterialCitation> matches, String notice) { }
    private record VectorMatch(MaterialCitation citation, double similarity) { }

    /** Only the bounded question is sent here. Searches never rebuild vectors or upload document contents. */
    public Ranking rank(List<LearningMaterial> materials, String query, List<MaterialCitation> lexical) {
        if (!configured()) return new Ranking(lexical, "当前使用关键词检索；语义检索未启用。");
        boolean omitted = materials.stream().anyMatch(m -> m.getSemanticSegments() != null && m.getSemanticSegments() > 0
                && (m.getEmbeddingJson() == null || m.getEmbeddingJson().length() > MAX_JSON_CHARS));
        String omittedNotice = omitted ? "部分语义索引超出 1MiB 预算或已变化，已保留关键词检索；请主动重新索引。" : "";
        List<LearningMaterial> compatible = materials.stream().filter(m -> m.getContent() != null
                && Objects.equals(m.getEmbeddingSignature(), client.signature()) && m.getEmbeddingJson() != null
                && m.getEmbeddingJson().length() <= MAX_JSON_CHARS).toList();
        if (compatible.isEmpty()) return new Ranking(lexical, "当前使用关键词检索；没有匹配当前嵌入模型的索引，请主动重新索引。" + omittedNotice);
        try {
            var result = client.embed(List.of(query));
            if (result.size() != 1) throw new IllegalArgumentException("Invalid query vector");
            float[] question = result.getFirst();
            validate(question);
            List<VectorMatch> semantic = new ArrayList<>();
            int malformed = 0;
            for (var material : compatible) {
                try {
                    if (material.getEmbeddingJson().length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Index size");
                    float[][] vectors = json.readValue(material.getEmbeddingJson(), float[][].class);
                    if (vectors.length < 1 || vectors.length > MAX_SEGMENTS
                            || !Objects.equals(material.getSemanticSegments(), vectors.length)) throw new IllegalArgumentException("Index count");
                    List<VectorMatch> validMaterial = new ArrayList<>();
                    for (int i = 0; i < vectors.length; i++) {
                        validate(vectors[i]);
                        int start = i * 600;
                        if (start >= material.getContent().length()) throw new IllegalArgumentException("Index boundary");
                        double similarity = cosine(question, vectors[i]);
                        if (similarity >= client.minSimilarity()) {
                            String excerpt = material.getContent().substring(start, Math.min(start + 700, material.getContent().length()));
                            validMaterial.add(new VectorMatch(new MaterialCitation("M" + material.getId() + "-S" + (i + 1),
                                    material.getId(), material.getCourseId(), material.getName(), material.getStorageKey(),
                                    material.getPath(), i + 1, excerpt, 1), similarity));
                        }
                    }
                    semantic.addAll(validMaterial);
                } catch (Exception corrupt) { malformed++; }
            }
            semantic.sort(Comparator.comparingDouble(VectorMatch::similarity).reversed()
                    .thenComparing(m -> m.citation().materialId()).thenComparing(m -> m.citation().segment()));
            Map<String, MaterialCitation> citations = new HashMap<>();
            Map<String, Double> scores = new HashMap<>();
            for (int i = 0; i < lexical.size(); i++) {
                var citation = lexical.get(i); citations.put(citation.reference(), citation);
                scores.merge(citation.reference(), 1.0 / (60 + i + 1), Double::sum);
            }
            for (int i = 0; i < semantic.size(); i++) {
                var citation = semantic.get(i).citation(); citations.putIfAbsent(citation.reference(), citation);
                scores.merge(citation.reference(), 1.0 / (60 + i + 1), Double::sum);
            }
            List<MaterialCitation> ranked = new ArrayList<>(citations.values());
            ranked.sort(Comparator.<MaterialCitation>comparingDouble(c -> scores.get(c.reference())).reversed()
                    .thenComparingInt(MaterialCitation::materialId).thenComparingInt(MaterialCitation::segment));
            // score is a relative ranking value, never a claimed answer-confidence percentage.
            ranked.replaceAll(c -> new MaterialCitation(c.reference(), c.materialId(), c.courseId(), c.name(), c.storageKey(),
                    c.path(), c.segment(), c.excerpt(), (int) Math.round(scores.get(c.reference()) * 1_000_000)));
            int coveredSegments = compatible.stream().map(LearningMaterial::getSemanticSegments)
                    .filter(Objects::nonNull).filter(count -> count > 0 && count <= MAX_SEGMENTS).mapToInt(Integer::intValue).max().orElse(0);
            return new Ranking(List.copyOf(ranked), "已合并关键词与有界语义索引；本次每份资料最多检索实际已索引的前 " + coveredSegments
                    + " 段，具体覆盖见资料说明；分数不是准确率。"
                    + (malformed > 0 ? "部分语义索引无效，已退回文字检索。" : "") + omittedNotice);
        } catch (Exception unavailable) {
            return new Ranking(lexical, "嵌入服务暂不可用，本次已退回关键词检索。" + omittedNotice);
        }
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != client.dimensions()) throw new IllegalArgumentException("Vector dimensions");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Vector finite");
            norm += (double) value * value;
        }
        if (!Double.isFinite(norm) || norm < 1e-12) throw new IllegalArgumentException("Vector zero");
    }

    static double cosine(float[] left, float[] right) {
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) { dot += (double) left[i] * right[i]; a += (double) left[i] * left[i]; b += (double) right[i] * right[i]; }
        return dot / Math.sqrt(a * b);
    }
}
