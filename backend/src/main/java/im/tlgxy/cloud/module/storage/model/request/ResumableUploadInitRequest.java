package im.tlgxy.cloud.module.storage.model.request;

import jakarta.validation.constraints.*;
import java.util.List;

/** Client cannot choose actor, grant, physical root, staging path or chunk size. */
public record ResumableUploadInitRequest(
        @NotBlank @Size(max = 128) String storageKey,
        @NotBlank @Size(max = 1000) String path,
        @NotBlank @Size(max = 255) String name,
        @Min(1) @Max(2147483648L) long size,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifestHash,
        @NotEmpty @Size(max = 512) List<@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String> chunkHashes) { }
