package io.studentcloud.assistant;

/** 校验知识的原始回答来源，阻止私人上下文经修订后被直接共享。
 *
 * The composition root supplies provenance rules; reviewed text alone cannot erase a private origin.
 */
@FunctionalInterface
public interface KnowledgeSourcePolicy {
    void requirePublishable(long feedbackId);
}
