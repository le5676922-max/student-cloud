package im.tlgxy.cloud.module.ai.model.automation;

import jakarta.validation.constraints.*;
import java.util.List;

public final class Automation {
    private Automation() { }
    public record Request(@NotBlank @Pattern(regexp = "ORGANIZE_FILES|IMPORT_LEARNING|RENAME_BY_DATE") String type,
                          @NotBlank @Size(max = 100) String storageKey,
                          @NotBlank @Size(max = 500) String path,
                          @Positive Integer courseId, @Size(max = 200) String tags) { }
    public record Confirmation(@AssertTrue(message = "请明确确认任务后再执行") boolean confirmed) { }
    public record Step(String action, String storageKey, String sourcePath, String targetPath,
                       Integer courseId, String tags, long size, long modifiedAt, String locationToken) { }
    public record StepResult(int index, boolean success, String message) { }
    public record View(String id, String title, String status, List<Step> steps,
                       List<StepResult> results, long createdAt, long expiresAt, String notice) { }
}
