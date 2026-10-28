package io.studentcloud.drive.application;

import java.time.Instant;

/** A local-storage application accepts a name, never a caller-selected physical directory. */
public record LocalStorageRequestView(long id, long requesterId, long workspaceId, String name, String status,
                                      Long mountId, Long decidedBy, String reason, Instant createdAt, Instant decidedAt) { }
