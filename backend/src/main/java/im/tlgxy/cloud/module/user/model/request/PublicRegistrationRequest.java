package im.tlgxy.cloud.module.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** No role, grants, enabled flag or user identifiers can be set by a public caller. */
public record PublicRegistrationRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String emailCode,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(min = 8, max = 128) String confirmPassword,
        @Size(max = 100) String nickname,
        @NotBlank @Pattern(regexp = "TENANT_ADMIN|USER") String registrationType,
        @Size(max = 80) String workspaceName,
        @Pattern(regexp = "INVITE|NAME") String joinType,
        @Size(max = 80) String joinValue) { }
