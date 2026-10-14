package im.tlgxy.cloud.module.ai.model.automation;

import jakarta.validation.constraints.*;

public record AgentScheduleRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Pattern(regexp = "ORGANIZE_FILES|IMPORT_LEARNING|RENAME_BY_DATE") String type,
        @NotBlank @Size(max = 100) String storageKey,
        @NotBlank @Size(max = 500) String path,
        @Positive Integer courseId,
        @Size(max = 200) String tags,
        @NotNull @Min(1) @Max(7) Integer dayOfWeek,
        @NotNull @Min(0) @Max(23) Integer hourOfDay,
        @NotNull @Min(0) @Max(59) Integer minuteOfHour) { }
