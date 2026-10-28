package io.studentcloud.assistant;

/** The composition root supplies provenance rules; reviewed text alone cannot erase a private origin. */
@FunctionalInterface
public interface KnowledgeSourcePolicy {
    void requirePublishable(long feedbackId);
}
