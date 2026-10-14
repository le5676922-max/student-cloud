package im.tlgxy.cloud.module.storage.model.request;

import jakarta.validation.constraints.*;

public record ResumableUploadCapabilitiesRequest(
        @NotBlank @Size(max = 128) String storageKey,
        @NotBlank @Size(max = 1000) String path,
        @NotBlank @Size(max = 255) String name,
        @Min(1) long size) { }
