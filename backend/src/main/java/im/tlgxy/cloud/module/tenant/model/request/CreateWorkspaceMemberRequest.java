package im.tlgxy.cloud.module.tenant.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceMemberRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @Size(max = 100) String nickname,
        @NotBlank @Size(min = 8, max = 100) String password) { }
