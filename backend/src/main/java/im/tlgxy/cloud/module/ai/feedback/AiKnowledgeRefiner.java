package im.tlgxy.cloud.module.ai.feedback;

/** Pure editing boundary: implementations must not publish or mark an answer as verified. */
public interface AiKnowledgeRefiner {
    String refine(String question, String administratorAnswer);
}
