package im.tlgxy.cloud.module.tenant.model.request;

import jakarta.validation.constraints.NotNull;

public record ReviewWorkspaceJoinRequest(@NotNull Boolean approve) { }
