package im.tlgxy.cloud.module.support;

/** Notification metadata only. Conversation content is read through the authorized history API. */
public record SupportChangedEvent(String conversationId, int ownerUserId) { }
