package im.tlgxy.cloud.module.ai.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;

/** RAG 知识检索结果。 */
@Data
@AllArgsConstructor
public class AiKnowledgeResult {

    private String id;

    private String title;

    private String content;

    private String source;

    private String audience;

    private double score;
}
