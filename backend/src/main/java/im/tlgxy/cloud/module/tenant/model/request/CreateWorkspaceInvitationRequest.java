package im.tlgxy.cloud.module.tenant.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateWorkspaceInvitationRequest(
        @Min(1) @Max(30) Integer expiresInDays,
        @Min(1) @Max(100) Integer maxUses) { }
