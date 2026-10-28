package io.studentcloud.support;

/** No other account's email, profile, AI history, attachments or memory is included. */
public record SupportConversation(String id, long requesterId, long workspaceId, String subject, SupportState state,
                                  Long assignedAdminId, long createdAt, long updatedAt, Long closedAt) { }
