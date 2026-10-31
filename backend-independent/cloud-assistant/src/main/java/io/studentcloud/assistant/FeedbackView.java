package io.studentcloud.assistant;

/** 向审核界面提供问题与修订状态，不返回用户敏感身份资料。
 *
 * Review data omits email, login names, session material and private user profiles.
 */
public record FeedbackView(long id, String question, String answer, String correctedAnswer, String note, FeedbackStatus status,
                           Long workspaceId, Long courseId, long version, long createdAt, long updatedAt) { }
