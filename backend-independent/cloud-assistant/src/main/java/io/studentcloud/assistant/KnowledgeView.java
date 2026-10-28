package io.studentcloud.assistant;

public record KnowledgeView(long id, long feedbackId, String title, String answer, KnowledgeScope scope,
                            Long workspaceId, Long courseId, long version, Long revokedAt, Long supersededAt, long createdAt) { }
