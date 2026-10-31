package io.studentcloud.assistant;

/** 返回反馈提交结果与重复提交标记，不包含账号秘密。 */
public record FeedbackReceipt(long id, FeedbackStatus status, long createdAt, boolean duplicate) { }
