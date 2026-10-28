package io.studentcloud.assistant;

/** Review data omits email, login names, session material and private user profiles. */
public record FeedbackView(long id, String question, String answer, String correctedAnswer, String note, FeedbackStatus status,
                           Long workspaceId, Long courseId, long version, long createdAt, long updatedAt) { }
