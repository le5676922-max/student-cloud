package io.studentcloud.support;

import io.studentcloud.account.Role;

/** Body is plain text. Rendering adapters must not interpret it as trusted HTML. */
public record SupportMessage(String id, String conversationId, long messageNo, long senderId, Role senderRole,
                             String clientMessageId, String body, long createdAt) { }
