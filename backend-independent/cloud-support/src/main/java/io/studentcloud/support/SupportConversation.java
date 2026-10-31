package io.studentcloud.support;

/** 承载人工服务会话的申请人、接待人和生命周期状态。 No other account's email, profile, AI history, attachments or memory is included. */
public record SupportConversation(String id, long requesterId, long workspaceId, String subject, SupportState state,
                                  Long assignedAdminId, long createdAt, long updatedAt, Long closedAt) { }
