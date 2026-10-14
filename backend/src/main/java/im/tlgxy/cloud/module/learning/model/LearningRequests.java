package im.tlgxy.cloud.module.learning.model;

import jakarta.validation.constraints.*;

public final class LearningRequests {
    private LearningRequests() { }
    public record Course(@NotBlank @Size(max = 100) String name,
                         @NotBlank @Size(max = 40) String semester) { }
    public record Material(@NotNull @Positive Integer courseId,
                           @NotBlank @Size(max = 100) String storageKey,
                           @NotBlank @Size(max = 500) String path,
                           @NotNull @Size(max = 200) String tags) { }
    public record Relink(@NotBlank @Size(max = 100) String storageKey,
                         @NotBlank @Size(max = 500) String path) { }
}
