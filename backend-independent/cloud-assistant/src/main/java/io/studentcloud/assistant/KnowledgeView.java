package io.studentcloud.assistant;

/** 表示审核知识的版本、发布范围及撤回状态。 */
public record KnowledgeView(long id, long feedbackId, String title, String answer, KnowledgeScope scope,
                            Long workspaceId, Long courseId, long version, Long revokedAt, Long supersededAt, long createdAt) { }
