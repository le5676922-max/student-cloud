package io.studentcloud.drive.application;

import java.time.Instant;

/**
 * 返回成员云盘开通申请的审批状态与处理记录。
 * Request status only; no email, password, host path or another member's files.
 */
public record CloudAccessRequestView(long id, long requesterId, long mountId, long workspaceId, String status,
                                     Long decidedBy, String reason, Instant createdAt, Instant decidedAt) { }
