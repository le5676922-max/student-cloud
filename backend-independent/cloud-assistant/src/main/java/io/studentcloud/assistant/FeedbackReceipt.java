package io.studentcloud.assistant;

public record FeedbackReceipt(long id, FeedbackStatus status, long createdAt, boolean duplicate) { }
