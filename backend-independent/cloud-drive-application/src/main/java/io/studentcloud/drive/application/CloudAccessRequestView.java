package io.studentcloud.drive.application;

import java.time.Instant;

/** Request status only; no email, password, host path or another member's files. */
public record CloudAccessRequestView(long id, long requesterId, long mountId, long workspaceId, String status,
                                     Long decidedBy, String reason, Instant createdAt, Instant decidedAt) { }
