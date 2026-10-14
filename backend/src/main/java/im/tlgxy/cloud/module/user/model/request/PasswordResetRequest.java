package im.tlgxy.cloud.module.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Password recovery is bound to verified email ownership, never to a caller-selected user ID. */
public record PasswordResetRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String emailCode,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(min = 8, max = 128) String confirmPassword) { }
