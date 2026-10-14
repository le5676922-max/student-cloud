package im.tlgxy.cloud.module.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationEmailCodeRequest(@NotBlank @Size(max = 254) String email) { }
